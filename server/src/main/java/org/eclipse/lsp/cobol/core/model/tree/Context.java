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
package org.eclipse.lsp.cobol.core.model.tree;

import java.util.List;

/**
 * The interface represents structure that can be defined and used.
 */
public interface Context {
  /**
   * Get structure definitions.
   *
   * @return the list of tree nodes
   */
  List<? extends Node> getDefinitions();

  /**
   * Get structure usages.
   *
   * @return the list of tree nodes
   */
  List<? extends Node> getUsages();
}
