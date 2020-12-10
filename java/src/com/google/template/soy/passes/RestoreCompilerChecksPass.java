/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.passes;

import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.AbstractLocalVarDefn;
import com.google.template.soy.exprtree.ExprNode.Kind;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.GlobalNode;
import com.google.template.soy.exprtree.ListComprehensionNode;
import com.google.template.soy.exprtree.TemplateLiteralNode;
import com.google.template.soy.exprtree.VeLiteralNode;
import com.google.template.soy.soytree.CallBasicNode;
import com.google.template.soy.soytree.ForNonemptyNode;
import com.google.template.soy.soytree.LetNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyTreeUtils;

/**
 * Reimplement certain compiler checks that were removed from the compiler when the distinction
 * between $ var refs and non-$ globals was removed.
 */
@RunAfter(RestoreGlobalsPass.class)
public final class RestoreCompilerChecksPass implements CompilerFilePass {

  private static final SoyErrorKind DATA_ATTRIBUTE_ONLY_ALLOWED_ON_STATIC_CALLS =
      SoyErrorKind.of("The `data` attribute is only allowed on static calls.");

  private static final SoyErrorKind MUST_BE_DOLLAR_IDENT =
      SoyErrorKind.of("Name must begin with a ''$''.");

  private static final SoyErrorKind MUST_BE_CONSTANT =
      SoyErrorKind.of("Expected constant identifier.");

  private final ErrorReporter errorReporter;

  public RestoreCompilerChecksPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    // Turn unresolved global nodes into template literal nodes. This previously happened
    // in the parser but now must happen after Visitor runs.
    SoyTreeUtils.getAllNodesOfType(file, CallBasicNode.class).stream()
        .filter(callNode -> callNode.getCalleeExpr().getRoot().getKind() == Kind.GLOBAL_NODE)
        .forEach(
            callNode -> {
              GlobalNode global = (GlobalNode) callNode.getCalleeExpr().getRoot();
              callNode.setCalleeExpr(
                  new ExprRootNode(
                      new TemplateLiteralNode(
                          global.getIdentifier(),
                          global.getSourceLocation(),
                          /* isSynthetic= */ true)));
            });

    // Validate CallBasicNode data="expr". This previously happened in the CallBasicNode
    // constructor but now must happen after Visitor runs.
    SoyTreeUtils.getAllNodesOfType(file, CallBasicNode.class).stream()
        .filter(callNode -> callNode.isPassingData() && !callNode.isStaticCall())
        .forEach(
            callNode ->
                errorReporter.report(
                    callNode.getOpenTagLocation(), DATA_ATTRIBUTE_ONLY_ALLOWED_ON_STATIC_CALLS));

    // Enforce certain symbols start with $, to match previous parser rules.
    SoyTreeUtils.getAllNodesOfType(file, LetNode.class).stream()
        .map(LetNode::getVar)
        .forEach(this::checkDollarIdent);
    SoyTreeUtils.getAllNodesOfType(file, ForNonemptyNode.class)
        .forEach(
            forNode -> {
              checkDollarIdent(forNode.getVar());
              if (forNode.getIndexVar() != null) {
                checkDollarIdent(forNode.getIndexVar());
              }
            });
    SoyTreeUtils.getAllNodesOfType(file, ListComprehensionNode.class)
        .forEach(
            listNode -> {
              checkDollarIdent(listNode.getListIterVar());
              if (listNode.getIndexVar() != null) {
                checkDollarIdent(listNode.getIndexVar());
              }
            });

    // ve(...) will now parse if ... starts with "$". But that's an error.
    SoyTreeUtils.getAllNodesOfType(file, VeLiteralNode.class)
        .forEach(
            veNode -> {
              if (veNode.getName().identifier().startsWith("$")) {
                errorReporter.report(veNode.getName().location(), MUST_BE_CONSTANT);
              }
            });
  }

  private void checkDollarIdent(AbstractLocalVarDefn<?> localVar) {
    if (!localVar.getOriginalName().startsWith("$")) {
      errorReporter.report(localVar.nameLocation(), MUST_BE_DOLLAR_IDENT);
    }
  }
}