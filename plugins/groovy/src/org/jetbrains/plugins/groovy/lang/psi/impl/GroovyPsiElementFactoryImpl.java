/*
 * Copyright 2000-2007 JetBrains s.r.o.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.plugins.groovy.lang.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.GroovyFileType;
import org.jetbrains.plugins.groovy.lang.groovydoc.psi.api.GrDocComment;
import org.jetbrains.plugins.groovy.lang.groovydoc.psi.api.GrDocMemberReference;
import org.jetbrains.plugins.groovy.lang.groovydoc.psi.api.GrDocTag;
import org.jetbrains.plugins.groovy.lang.parser.GroovyElementTypes;
import org.jetbrains.plugins.groovy.lang.psi.*;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.*;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrCodeBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.*;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrMethodCallExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.params.GrParameter;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinition;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinitionBody;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.toplevel.GrTopStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.toplevel.imports.GrImportStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.toplevel.packaging.GrPackageDefinition;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrClassTypeElement;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrCodeReferenceElement;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeElement;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil;
import org.jetbrains.plugins.groovy.refactoring.GroovyNamesUtil;
import org.jetbrains.plugins.groovy.refactoring.GroovyRefactoringUtil;

/**
 * @author ven
 */
public class GroovyPsiElementFactoryImpl extends GroovyPsiElementFactory implements ProjectComponent {
  Project myProject;

  public GroovyPsiElementFactoryImpl(Project project) {
    myProject = project;
  }

  private static String DUMMY = "dummy.";

  @Nullable
  public PsiElement createReferenceNameFromText(String refName) {
    PsiFile file = createGroovyFile("a." + refName);
    GrTopStatement statement = ((GroovyFileBase) file).getTopStatements()[0];
    if (!(statement instanceof GrReferenceExpression)) return null;

    return ((GrReferenceExpression) statement).getReferenceNameElement();
  }

  public PsiElement createDocMemberReferenceNameFromText(String idText) {
    PsiFile file = createGroovyFile("/** @see A#" + idText + " */");
    PsiElement element = file.getFirstChild();
    assert element instanceof GrDocComment;
    GrDocTag tag = PsiTreeUtil.getChildOfType(element, GrDocTag.class);
    assert tag != null : "Doc tag points to null";
    GrDocMemberReference reference = PsiTreeUtil.getChildOfType(tag, GrDocMemberReference.class);
    assert reference != null : "DocMemberReference ponts to null";
    return reference.getReferenceNameElement();
  }

  public GrCodeReferenceElement createReferenceElementFromText(String refName) {
    PsiFile file = createGroovyFile("(" + refName + " " + ")foo");
    GrTypeElement typeElement = ((GrTypeCastExpression) ((GroovyFileBase) file).getTopStatements()[0]).getCastTypeElement();
    return ((GrClassTypeElement) typeElement).getReferenceElement();
  }

  public GrReferenceExpression createReferenceExpressionFromText(String idText) {
    PsiFile file = createGroovyFile(idText);
    return (GrReferenceExpression) ((GroovyFileBase) file).getTopStatements()[0];
  }

  public GrExpression createExpressionFromText(String text) {
    GroovyFileImpl file = (GroovyFileImpl) createGroovyFile(text);
    assert file.getTopStatements()[0] instanceof GrExpression;
    return (GrExpression) ((GroovyFileBase) file).getTopStatements()[0];
  }

  public GrVariableDeclaration createVariableDeclaration(String[] modifiers, GrExpression initializer, PsiType type, String... identifiers) {
    StringBuffer text = writeModifiers(modifiers);

    if (type != null) {
      type = TypesUtil.unboxPrimitiveTypeWrapper(type);
      final String typeText = getTypeText(type);
      int lastDot = typeText.lastIndexOf('.');
      int idx = 0 < lastDot && lastDot < typeText.length() - 1 ? lastDot + 1 : 0;
      if (Character.isLowerCase(typeText.charAt(idx)) &&
          !GroovyNamesUtil.isKeyword(typeText)) { //primitive type
        text.append("def ");
      }
      text.append(typeText).append(" ");
    } else {
      text.append("def ");
    }

    int i = 1;
    for (String identifier : identifiers) {
      text.append(identifier);
      if (i < identifiers.length) {
        text.append(", ");
      }
      i++;
    }
    GrExpression expr;

    if (initializer != null) {
      if (initializer instanceof GrApplicationStatement) {
        expr = createMethodCallByAppCall(((GrApplicationStatement) initializer));
      } else {
        expr = initializer;
      }
      text.append(" = ").append(expr.getText());
    }

    PsiFile file = createGroovyFile(text.toString());
    return ((GrVariableDeclaration) ((GroovyFileBase) file).getTopStatements()[0]);
  }

  public GrVariableDeclaration createFieldDeclaration(String[] modifiers, String identifier, GrExpression initializer, PsiType type) {
    final String varDeclaration = createVariableDeclaration(modifiers, initializer, type, identifier).getText();

    final GroovyFileBase file = (GroovyFileBase) createGroovyFile("class A { " + varDeclaration + "}");
    final GrTypeDefinitionBody body = file.getTypeDefinitions()[0].getBody();
    return (GrVariableDeclaration) body.getMemberDeclarations()[0];
  }

  private StringBuffer writeModifiers(String[] modifiers) {
    StringBuffer text = new StringBuffer();
    if (!(modifiers == null || modifiers.length == 0)) {
      for (String modifier : modifiers) {
        text.append(modifier);
        text.append(" ");
      }
    }
    return text;
  }

  private String getTypeText(PsiType type) {
    final String canonical = type.getCanonicalText();
    return canonical != null ? canonical : type.getPresentableText();
  }

  @Nullable
  public GrTopStatement createTopElementFromText(String text) {
    PsiFile dummyFile = PsiManager.getInstance(myProject).getElementFactory().createFileFromText(DUMMY + GroovyFileType.GROOVY_FILE_TYPE.getDefaultExtension(),
        text);
    final PsiElement firstChild = dummyFile.getFirstChild();
    if (!(firstChild instanceof GrTopStatement)) return null;

    return (GrTopStatement) firstChild;
  }

  public GrClosableBlock createClosureFromText(String closureText) throws IncorrectOperationException {
    PsiFile psiFile = createDummyFile(closureText);
    ASTNode node = psiFile.getFirstChild().getNode();
    if (node.getElementType() != GroovyElementTypes.CLOSABLE_BLOCK)
      throw new IncorrectOperationException("Invalid all text");
    return (GrClosableBlock) node.getPsi();
  }

  private GroovyFileImpl createDummyFile(String s, boolean isPhisical) {
    return (GroovyFileImpl) PsiManager.getInstance(myProject).getElementFactory().createFileFromText("DUMMY__." + GroovyFileType.GROOVY_FILE_TYPE.getDefaultExtension(), GroovyFileType.GROOVY_FILE_TYPE, s, System.currentTimeMillis(), isPhisical);
  }

  private GroovyFileImpl createDummyFile(String s) {
    return createDummyFile(s, false);
  }

  public GrParameter createParameter(String name, @Nullable String typeText, GroovyPsiElement context) throws IncorrectOperationException {
    String fileText;
    if (typeText != null) {
      fileText = "def foo(" + typeText + " " + name + ") {}";
    } else {
      fileText = "def foo(" + name + ") {}";
    }
    GroovyFileImpl groovyFile = createDummyFile(fileText);
    groovyFile.setContext(context);

    ASTNode node = groovyFile.getFirstChild().getNode();
    if (node.getElementType() != GroovyElementTypes.METHOD_DEFINITION)
      throw new IncorrectOperationException("Invalid all text");
    return ((GrMethod) node.getPsi()).getParameters()[0];
  }

  public GrCodeReferenceElement createTypeOrPackageReference(String qName) {
    final GroovyFileBase file = createDummyFile("def " + qName + " i");
    GrVariableDeclaration varDecl = (GrVariableDeclaration) file.getTopStatements()[0];
    final GrClassTypeElement typeElement = (GrClassTypeElement) varDecl.getTypeElementGroovy();
    assert typeElement != null;
    return typeElement.getReferenceElement();
  }

  public GrTypeDefinition createTypeDefinition(String text) throws IncorrectOperationException {
    final GroovyFileBase file = createDummyFile(text);
    final GrTypeDefinition[] classes = file.getTypeDefinitions();
    if (classes.length != 1) throw new IncorrectOperationException("Incorrect type definition text");
    return classes[0];
  }

  public GrTypeElement createTypeElement(String typeText) throws IncorrectOperationException {
    final GroovyFileBase file = createDummyFile("def " + typeText + " someVar");

    GrTopStatement[] topStatements = file.getTopStatements();

    if (topStatements == null || topStatements.length == 0) throw new IncorrectOperationException("");
    GrTopStatement statement = topStatements[0];

    if (!(statement instanceof GrVariableDeclaration)) throw new IncorrectOperationException("");
    GrVariableDeclaration decl = (GrVariableDeclaration) statement;
    return decl.getTypeElementGroovy();
  }

  public GrTypeElement createTypeElement(PsiType type) throws IncorrectOperationException {
    final String typeText = getTypeText(type);
    if (typeText == null)
      throw new IncorrectOperationException("Cannot create type element: cannot obtain text for type");
    return createTypeElement(typeText);
  }

  public GrParenthesizedExpression createParenthesizedExpr(GrExpression newExpr) {
    return ((GrParenthesizedExpression) getInstance(myProject).createExpressionFromText("(" + newExpr.getText() + ")"));
  }

  public PsiElement createStringLiteral(String text) {
    return ((GrReferenceExpression) createDummyFile("a.'" + text + "'").getTopStatements()[0]).getReferenceNameElement();
  }

  public PsiElement createModifierFormText(String name) {
    final GroovyFileBase file = createDummyFile(name + " def foo () {}");
    return file.getTopLevelDefinitions()[0].getFirstChild().getFirstChild();
  }

  public GrCodeBlock createMethodBodyFormText(String text) {
    final GroovyFileBase file = createDummyFile("def foo () {" + text + "}");
    final GrMethod method = (GrMethod) file.getTopLevelDefinitions()[0];
    return method.getBlock();
  }

  public GrVariableDeclaration createSimpleVariableDeclaration(String name, String typeText) {
    String classText = "";
    if (Character.isLowerCase(typeText.charAt(0))) {
      classText = "class A { def " + typeText + " " + name + "}";
    } else {
      classText = "class A { " + typeText + " " + name + "}";
    }

    GroovyFileBase file = (GroovyFileBase) createGroovyFile(classText);
    final GrTypeDefinitionBody body = file.getTypeDefinitions()[0].getBody();
    return (GrVariableDeclaration) body.getMemberDeclarations()[0];
  }

  public GrReferenceElement createPackageReferenceElementFromText(String newPackageName) {
    return ((GrPackageDefinition) createDummyFile("package " + newPackageName).getTopStatements()[0]).getPackageReference();
  }

  public PsiElement createDotToken(String newDot) {
    return createReferenceExpressionFromText("a" + newDot + "b").getDotToken();
  }

  public GrMethod createMethodFromText(@NotNull String methodText) {
    GroovyFileBase file = createDummyFile(methodText);
    GrTopLevelDefintion defintion = file.getTopLevelDefinitions()[0];
    assert defintion != null && defintion instanceof GrMethod;
    return ((GrMethod) defintion);
  }

  public PsiFile createGroovyFile(String idText) {
    return createGroovyFile(idText, false, null);
  }

  public GroovyFile createGroovyFile(String idText, boolean isPhisical, PsiElement context) {
    GroovyFileImpl file = createDummyFile(idText, isPhisical);
    file.setContext(context);
    return file;
  }

  public void projectOpened() {
  }

  public void projectClosed() {
  }

  @NonNls
  @NotNull
  public String getComponentName() {
    return "Groovy Element Factory";
  }

  public void initComponent() {
  }

  public void disposeComponent() {
  }

  public PsiElement createWhiteSpace() {
    PsiFile dummyFile = PsiManager.getInstance(myProject).getElementFactory().createFileFromText(DUMMY + GroovyFileType.GROOVY_FILE_TYPE.getDefaultExtension(),
        " ");
    return dummyFile.getFirstChild();
  }

  @NotNull
  public PsiElement createLineTerminator(int length) {

    String text = length <= 1 ? "\n" : "";
    if (length > 1) {
      StringBuffer buffer = new StringBuffer();
      for (; length > 0; length--) {
        buffer.append("\n");
      }
      text = buffer.toString();
    }

    PsiFile dummyFile = PsiManager.getInstance(myProject).getElementFactory().createFileFromText(DUMMY + GroovyFileType.GROOVY_FILE_TYPE.getDefaultExtension(),
        text);
    PsiElement child = dummyFile.getFirstChild();
    assert child != null;
    return child;
  }

  public GrArgumentList createExpressionArgumentList(GrExpression... expressions) {
    StringBuffer text = new StringBuffer();
    text.append("ven (");
    for (GrExpression expression : expressions) {
      text.append(expression.getText()).append(", ");
    }
    if (expressions.length > 0) {
      text.delete(text.length() - 2, text.length());
    }
    text.append(")");
    PsiFile file = createGroovyFile(text.toString());
    assert file.getChildren()[0] != null && (file.getChildren()[0] instanceof GrMethodCallExpression);
    return (((GrMethodCallExpression) file.getChildren()[0])).getArgumentList();
  }

  public GrStatement createStatementFromText(String text) {
    PsiFile file = createGroovyFile(text);
    assert ((GroovyFileBase) file).getTopStatements()[0] instanceof GrStatement;
    return (GrStatement) ((GroovyFileBase) file).getTopStatements()[0];
  }

  public GrBlockStatement createBlockStatement(@NonNls GrStatement... statements) {
    StringBuffer text = new StringBuffer();
    text.append("while (true) { \n");
    for (GrStatement statement : statements) {
      text.append(statement.getText()).append("\n");
    }
    text.append("}");
    PsiFile file = createGroovyFile(text.toString());
    assert file.getChildren()[0] != null && (file.getChildren()[0] instanceof GrWhileStatement);
    return (GrBlockStatement) ((GrWhileStatement) file.getChildren()[0]).getBody();
  }

  public GrMethodCallExpression createMethodCallByAppCall(GrApplicationStatement callExpr) {
    StringBuffer text = new StringBuffer();
    text.append(callExpr.getFunExpression().getText());
    text.append("(");
    for (GrExpression expr : callExpr.getArguments()) {
      text.append(GroovyRefactoringUtil.getUnparenthesizedExpr(expr).getText()).append(", ");
    }
    if (callExpr.getArguments().length > 0) {
      text.delete(text.length() - 2, text.length());
    }
    text.append(")");
    PsiFile file = createGroovyFile(text.toString());
    assert file.getChildren()[0] != null && (file.getChildren()[0] instanceof GrMethodCallExpression);
    return ((GrMethodCallExpression) file.getChildren()[0]);
  }

  public GrImportStatement createImportStatementFromText(String qName, boolean isStatic, boolean isOnDemand, String alias) {
    final String text = "import " + (isStatic ? "static " : "") + qName + (isOnDemand ? ".*" : "") +
        (alias != null && alias.length() > 0 ? " as " + alias : "");

    PsiFile dummyFile = PsiManager.getInstance(myProject).getElementFactory().createFileFromText(DUMMY + GroovyFileType.GROOVY_FILE_TYPE.getDefaultExtension(),
        text);
    return ((GrImportStatement) dummyFile.getFirstChild());
  }

  public GrImportStatement createImportStatementFromText(@NotNull String text) {
    PsiFile dummyFile = PsiManager.getInstance(myProject).getElementFactory().createFileFromText(DUMMY + GroovyFileType.GROOVY_FILE_TYPE.getDefaultExtension(),
        text);
    return ((GrImportStatement) dummyFile.getFirstChild());
  }


}
