/*
 * Copyright (c) 2020 Broadcom.
 * The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Broadcom, Inc. - initial API and implementation
 *
 */
package org.eclipse.lsp.cobol.core.engine;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.eclipse.lsp.cobol.core.CobolLexer;
import org.eclipse.lsp.cobol.core.CobolParser;
import org.eclipse.lsp.cobol.core.messages.MessageService;
import org.eclipse.lsp.cobol.core.model.*;
import org.eclipse.lsp.cobol.core.model.tree.EmbeddedCodeNode;
import org.eclipse.lsp.cobol.core.model.tree.Node;
import org.eclipse.lsp.cobol.core.model.tree.ProgramNode;
import org.eclipse.lsp.cobol.core.model.variables.Variable;
import org.eclipse.lsp.cobol.core.preprocessor.TextPreprocessor;
import org.eclipse.lsp.cobol.core.preprocessor.delegates.util.LocalityFindingUtils;
import org.eclipse.lsp.cobol.core.preprocessor.delegates.util.LocalityMappingUtils;
import org.eclipse.lsp.cobol.core.semantics.PredefinedVariableContext;
import org.eclipse.lsp.cobol.core.semantics.SemanticContext;
import org.eclipse.lsp.cobol.core.visitor.CobolVisitor;
import org.eclipse.lsp.cobol.core.visitor.EmbeddedLanguagesListener;
import org.eclipse.lsp.cobol.core.visitor.ParserListener;
import org.eclipse.lsp.cobol.service.CopybookConfig;
import org.eclipse.lsp.cobol.service.SubroutineService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.eclipse.lsp.cobol.core.model.tree.Node.hasType;
import static org.eclipse.lsp.cobol.core.model.tree.NodeType.EMBEDDED_CODE;
import static org.eclipse.lsp.cobol.core.model.tree.NodeType.PROGRAM;
import static org.eclipse.lsp.cobol.core.semantics.outline.OutlineNodeNames.FILLER_NAME;
import static org.eclipse.lsp.cobol.service.PredefinedCopybooks.PREF_IMPLICIT;

/**
 * This class is responsible for run the syntax and semantic analysis of an input cobol document.
 * Its run method used by the service facade layer CobolLanguageEngineFacade.
 */
@Slf4j
@Singleton
@SuppressWarnings("WeakerAccess")
public class CobolLanguageEngine {

  private final TextPreprocessor preprocessor;
  private final DefaultErrorStrategy defaultErrorStrategy;
  private final MessageService messageService;
  private final ParseTreeListener treeListener;
  private final SubroutineService subroutineService;
  private static final int PROCESS_CALLS_THRESHOLD = 10;

  @Inject
  public CobolLanguageEngine(
      TextPreprocessor preprocessor,
      DefaultErrorStrategy defaultErrorStrategy,
      MessageService messageService,
      ParseTreeListener treeListener,
      SubroutineService subroutineService) {
    this.preprocessor = preprocessor;
    this.defaultErrorStrategy = defaultErrorStrategy;
    this.messageService = messageService;
    this.treeListener = treeListener;
    this.subroutineService = subroutineService;
  }

  /**
   * Perform syntax and semantic analysis for the given text document
   *
   * @param documentUri unique resource identifier of the processed document
   * @param text the content of the document that should be processed
   * @param copybookConfig contains config info like: copybook processing mode, backend server
   * @return Semantic information wrapper object and list of syntax error that might send back to
   *     the client
   */
  @NonNull
  public ResultWithErrors<SemanticContext> run(
      @NonNull String documentUri, @NonNull String text, @NonNull CopybookConfig copybookConfig) {
    ThreadInterruptionUtil.checkThreadInterrupted();
    Timing.Builder timingBuilder = Timing.builder();
    timingBuilder.getPreprocessorTimer().start();
    List<SyntaxError> accumulatedErrors = new ArrayList<>();
    ExtendedDocument extendedDocument =
        preprocessor.process(documentUri, text, copybookConfig).unwrap(accumulatedErrors::addAll);
    timingBuilder.getPreprocessorTimer().stop();

    timingBuilder.getParserTimer().start();
    CobolLexer lexer = new CobolLexer(CharStreams.fromString(extendedDocument.getText()));
    lexer.removeErrorListeners();

    CommonTokenStream tokens = new CommonTokenStream(lexer);
    ParserListener listener = new ParserListener();
    lexer.addErrorListener(listener);

    CobolParser parser = getCobolParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(listener);
    parser.setErrorHandler(defaultErrorStrategy);
    parser.addParseListener(treeListener);

    CobolParser.StartRuleContext tree = parser.startRule();
    timingBuilder.getParserTimer().stop();

    timingBuilder.getSplittingLanguageTimer().start();
    Map<Token, EmbeddedCode> embeddedCodeParts = extractEmbeddedCode(listener, tree);
    timingBuilder.getSplittingLanguageTimer().stop();

    timingBuilder.getMappingTimer().start();
    Map<Token, Locality> positionMapping =
        getPositionMapping(documentUri, extendedDocument, tokens, embeddedCodeParts);
    timingBuilder.getMappingTimer().stop();

    timingBuilder.getVisitorTimer().start();
    CobolVisitor visitor =
        new CobolVisitor(
            documentUri,
            extendedDocument.getCopybooks(),
            tokens,
            positionMapping,
            embeddedCodeParts,
            messageService,
            subroutineService);
    List<Node> syntaxTree = visitor.visit(tree);
    SemanticContext context = visitor.finishAnalysis().unwrap(accumulatedErrors::addAll);
    timingBuilder.getVisitorTimer().stop();
    if (syntaxTree.size() == 1) {
      timingBuilder.getSyntaxTreeTimer().start();
      analyzeEmbeddedCode(syntaxTree, positionMapping, context.getConstants());
      Node rootNode = syntaxTree.get(0);
      accumulatedErrors.addAll(processSyntaxTree(rootNode));
      // This is a temporal solution only for compatibility
      // Definitions, usages and variables are set here for "Go to definition" feature and others
      List<Variable> definedVariables =
          rootNode
              .getDepthFirstStream()
              .filter(hasType(PROGRAM))
              .map(ProgramNode.class::cast)
              .map(ProgramNode::getDefinedVariables)
              .flatMap(Collection::stream)
              .collect(toList());
      context =
          context.toBuilder()
              .variables(collectVariables(definedVariables))
              .rootNode(rootNode)
              .build();
      timingBuilder.getSyntaxTreeTimer().stop();
    } else LOG.warn("The root node for syntax tree was not constructed");
    timingBuilder.getLateErrorProcessingTimer().start();
    accumulatedErrors.addAll(finalizeErrors(listener.getErrors(), positionMapping));
    accumulatedErrors.addAll(
        collectErrorsForCopybooks(accumulatedErrors, extendedDocument.getCopyStatements()));
    timingBuilder.getLateErrorProcessingTimer().stop();

    if (LOG.isDebugEnabled()) {
      Timing timing = timingBuilder.build();
      LOG.debug(
          "Timing for parsing {}. Preprocessor: {}, parser: {}, mapping: {}, visitor: {}, syntaxTree: {}, "
              + "late error processing: {}",
          documentUri,
          timing.getPreprocessorTime(),
          timing.getParserTime(),
          timing.getMappingTime(),
          timing.getVisitorTime(),
          timing.getSyntaxTreeTime(),
          timing.getLateErrorProcessingTime());
    }

    return new ResultWithErrors<>(
        context, accumulatedErrors.stream().map(this::constructErrorMessage).collect(toList()));
  }

  private List<SyntaxError> processSyntaxTree(Node rootNode) {
    List<SyntaxError> errors = new ArrayList<>();
    int processCalls = 0;
    do {
      errors.addAll(rootNode.process());
      processCalls++;
      if (processCalls > PROCESS_CALLS_THRESHOLD) throw new RuntimeException("Infinity loop in tree processing");
    } while (!rootNode.isProcessed());
    return errors;
  }

  private Map<Token, EmbeddedCode> extractEmbeddedCode(
      ParserListener listener, CobolParser.StartRuleContext tree) {
    EmbeddedLanguagesListener embeddedLanguagesListener =
        new EmbeddedLanguagesListener(defaultErrorStrategy, treeListener, listener);
    new ParseTreeWalker().walk(embeddedLanguagesListener, tree);
    return embeddedLanguagesListener.getEmbeddedCodeParts();
  }

  CobolParser getCobolParser(CommonTokenStream tokens) {

    ThreadInterruptionUtil.checkThreadInterrupted();
    return new CobolParser(tokens);
  }

  Map<Token, Locality> getPositionMapping(
      String documentUri,
      ExtendedDocument extendedDocument,
      CommonTokenStream tokens,
      Map<Token, EmbeddedCode> embeddedCodeParts) {
    ThreadInterruptionUtil.checkThreadInterrupted();
    return LocalityMappingUtils.createPositionMapping(
        tokens.getTokens(), extendedDocument.getDocumentMapping(), documentUri, embeddedCodeParts);
  }

  private void analyzeEmbeddedCode(
      List<Node> syntaxTree, Map<Token, Locality> mapping, PredefinedVariableContext constants) {
    syntaxTree.stream()
        .flatMap(Node::getDepthFirstStream)
        .filter(hasType(EMBEDDED_CODE))
        .map(EmbeddedCodeNode.class::cast)
        .collect(toList())
        .forEach(it -> it.analyzeTree(mapping, constants));
  }

  @NonNull
  private List<SyntaxError> finalizeErrors(
      @NonNull List<SyntaxError> errors, @NonNull Map<Token, Locality> mapping) {
    return errors.stream()
        .map(convertError(mapping))
        .filter(it -> it.getLocality() != null)
        .collect(toList());
  }

  @NonNull
  private Function<SyntaxError, SyntaxError> convertError(@NonNull Map<Token, Locality> mapping) {
    return err ->
        err.toBuilder()
            .locality(
                LocalityFindingUtils.findPreviousVisibleLocality(err.getOffendedToken(), mapping))
            .suggestion(messageService.getMessage(err.getSuggestion()))
            .build();
  }

  private List<SyntaxError> collectErrorsForCopybooks(
      List<SyntaxError> errors, Map<String, Locality> copyStatements) {
    return errors.stream()
        .filter(shouldRaise())
        .map(err -> raiseError(err, copyStatements))
        .flatMap(List::stream)
        .collect(toList());
  }

  private List<SyntaxError> raiseError(SyntaxError error, Map<String, Locality> copyStatements) {
    return Stream.of(error)
        .filter(shouldRaise())
        .map(err -> err.toBuilder().locality(copyStatements.get(err.getLocality().getCopybookId())))
        .map(SyntaxError.SyntaxErrorBuilder::build)
        .flatMap(err -> Stream.concat(raiseError(err, copyStatements).stream(), Stream.of(err)))
        .collect(toList());
  }

  private Predicate<SyntaxError> shouldRaise() {
    return err -> err.getLocality().getCopybookId() != null;
  }

  private SyntaxError constructErrorMessage(SyntaxError syntaxError) {
    return ofNullable(syntaxError.getMessageTemplate())
        .map(messageService::localizeTemplate)
        .map(message -> syntaxError.toBuilder().messageTemplate(null).suggestion(message).build())
        .orElse(syntaxError);
  }

  private List<Variable> collectVariables(Collection<Variable> definedVariables) {
    return definedVariables.stream()
        .filter(it -> !FILLER_NAME.equals(it.getName()))
        .filter(it -> !it.getDefinition().getUri().startsWith(PREF_IMPLICIT))
        .collect(toList());
  }
}
