/*
 * Copyright (c) 2021 Broadcom.
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

package org.eclipse.lsp.cobol.positive;

import com.google.common.util.concurrent.SimpleTimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.eclipse.lsp.cobol.service.delegates.validations.UseCaseUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This test checks that the files in the positive test set don't produce any {@link
 * RuntimeException} while analyzing them char by char. Disabled by default, to enable provide
 * <code>-Dtests.typing=true
 * </code> as a system property for the run configuration.
 */
@Slf4j
class TypingTest extends FileBasedTest {
  private static final String MODE_PROPERTY_NAME = "tests.typing";
  private static final String TEST_MODE = System.getProperty(MODE_PROPERTY_NAME);
  private static final int NUMBER_OF_GENERATED_TASKS = 1000;

  @Test
  void typingTest() {
    if (!Boolean.TRUE.toString().equals(TEST_MODE)) return;
    List<CobolText> textsToTest = getTextsToTest();
    final int size = textsToTest.size();
    for (int i = 0; i < size; i++) {
      CobolText cobolText = textsToTest.get(i);
      String name = cobolText.getFileName();
      LOG.info("Analyzing {}", name);
      final long start = System.currentTimeMillis();
      analyze(name, cobolText.getFullText());
      final long duration = System.currentTimeMillis() - start;
      LOG.info("{} analyzed in {}. Progress: {}/{}.",
          name,
          DurationFormatUtils.formatDurationHMS(duration),
          i + 1, size);
    }
  }

  private void analyze(String name, String fullText) {
    AtomicInteger processed = new AtomicInteger();
    UseCaseGenerator generator = new UseCaseGenerator(name, fullText, 1, processed);
    ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(1);
    int textSize = fullText.length();
    scheduled.scheduleAtFixedRate(() -> {
      int done = processed.get();
      String percents = String.format("%.2f", (float) 100 * done / textSize);
      LOG.info("{}% in work. Position {} of {}", percents, done, textSize);
    }, 10, 10, TimeUnit.MINUTES);
    ForkJoinPool.commonPool().invoke(generator);
    scheduled.shutdown();
  }

  private final class UseCaseGenerator extends RecursiveAction {
    private final String name;
    private final String fullText;
    private final int startPosition;
    private final AtomicInteger processed;

    UseCaseGenerator(String name, String fullText, int startPosition, AtomicInteger processed) {
      this.name = name;
      this.fullText = fullText;
      this.startPosition = startPosition;
      this.processed = processed;
    }

    @Override
    protected void compute() {
      int max = Math.min(startPosition + NUMBER_OF_GENERATED_TASKS - 1, fullText.length());
      List<RecursiveAction> actions = new ArrayList<>(NUMBER_OF_GENERATED_TASKS);
      for (int position = startPosition; position <= max; position++)
        actions.add(new UseCaseRun(name, fullText, startPosition, processed));
      if (max != fullText.length())
        actions.add(new UseCaseGenerator(name, fullText, max + 1, processed));
      ForkJoinTask.invokeAll(actions);
    }
  }

  private final class UseCaseRun extends RecursiveAction {
    private final String name;
    private final String fullText;
    private final int position;
    private final AtomicInteger processed;

    UseCaseRun(String name, String fullText, int position, AtomicInteger processed) {
      this.name = name;
      this.fullText = fullText;
      this.position = position;
      this.processed = processed;
    }

    @Override
    protected void compute() {
      processed.incrementAndGet();
      if (fullText.charAt(position - 1) == ' ') return;
      String text = fullText.substring(0, position);
      SimpleTimeLimiter timeLimiter = SimpleTimeLimiter.create(ForkJoinPool.commonPool());
      try {
        timeLimiter.callWithTimeout(() -> {
          UseCaseUtils.analyzeForErrors(name, text, getCopybooks());
          return null;
        }, 30, TimeUnit.SECONDS);
      } catch (Exception e) {
        LOG.error("Text that produced the error:\n{}", text, e);
      }
    }
  }
}
