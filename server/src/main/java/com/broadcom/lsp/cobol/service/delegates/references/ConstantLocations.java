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

package com.broadcom.lsp.cobol.service.delegates.references;

import com.broadcom.lsp.cobol.service.CobolDocumentModel;
import org.eclipse.lsp4j.Location;

import lombok.NonNull;
import java.util.List;
import java.util.Map;

/**
 * This class is a provider for predefined variables, used in the document. These variables defined
 * implicitly, so the definitions list should be empty.
 */
public class ConstantLocations implements SemanticLocations {

  @NonNull
  @Override
  public Map<String, List<Location>> references(@NonNull CobolDocumentModel document) {
    return document.getAnalysisResult().getConstantUsages();
  }

  @NonNull
  @Override
  public Map<String, List<Location>> definitions(@NonNull CobolDocumentModel document) {
    return Map.of();
  }

  @Override
  public boolean containsToken(@NonNull CobolDocumentModel document, @NonNull String token) {
    return document.getAnalysisResult().getConstantDefinitions().keySet().stream()
        .anyMatch(token::equalsIgnoreCase);
  }
}
