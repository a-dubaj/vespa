package ai.vespa.schemals.lsp.completion.provider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.lsp4j.CompletionItem;

import com.ibm.icu.impl.ICUService.Key;

import ai.vespa.schemals.context.EventCompletionContext;
import ai.vespa.schemals.index.Symbol;
import ai.vespa.schemals.index.Symbol.SymbolType;
import ai.vespa.schemals.lsp.completion.utils.CompletionUtils;
import ai.vespa.schemals.parser.ast.NL;
import ai.vespa.schemals.parser.ast.expression;
import ai.vespa.schemals.parser.ast.featureListElm;
import ai.vespa.schemals.parser.ast.openLbrace;
import ai.vespa.schemals.parser.rankingexpression.ast.feature;
import ai.vespa.schemals.schemadocument.resolvers.RankExpression.BuiltInFunctions;
import ai.vespa.schemals.schemadocument.resolvers.RankExpression.FunctionSignature;
import ai.vespa.schemals.schemadocument.resolvers.RankExpression.GenericFunction;
import ai.vespa.schemals.schemadocument.resolvers.RankExpression.SpecificFunction;
import ai.vespa.schemals.schemadocument.resolvers.RankExpression.argument.KeywordArgument;
import ai.vespa.schemals.tree.CSTUtils;
import ai.vespa.schemals.tree.SchemaNode;
import ai.vespa.schemals.tree.SchemaNode.LanguageType;
import ai.vespa.schemals.tree.rankingexpression.RankNode;

/**
 * RankingExpressionCompletion
 * We only perform very simple function name completion.
 */
public class RankingExpressionCompletion implements CompletionProvider {

    private boolean matchFunctionCompletion(EventCompletionContext context, SchemaNode clean) {
        if (context.triggerCharacter.equals('.')) return false;
        return (clean.getLanguageType() == LanguageType.RANK_EXPRESSION || (
            clean.getParent() != null && (
                clean.getParent().isASTInstance(expression.class) 
             || clean.getParent().isASTInstance(featureListElm.class)
             )
        ));
    }

    String getFunctionSignature(Symbol functionDefinition) {
        StringBuilder signature = new StringBuilder();
        SchemaNode definitionNode = functionDefinition.getNode();
        //signature.append(definitionNode.getText());
        SchemaNode it = definitionNode.getNextSibling();
        do {
            signature.append(it.getText());
            it = it.getNextSibling();
        } while(it != null && !it.isASTInstance(NL.class) && !it.isASTInstance(openLbrace.class));
        //signature.append(")");
        return signature.toString();
    }

    List<CompletionItem> getUserDefinedFunctions(EventCompletionContext context, SchemaNode node) {
        Optional<Symbol> scope = CSTUtils.findScope(node);
        if (scope.isEmpty())return List.of();

        Symbol scopeIterator = scope.get();

        Symbol myFunction = null;

        while (scopeIterator != null && scopeIterator.getType() != SymbolType.RANK_PROFILE) {
            // cannot use user defined functions in lambda
            if (scopeIterator.getType() == SymbolType.LAMBDA_FUNCTION) return List.of();

            if (scopeIterator.getType() == SymbolType.FUNCTION)
                myFunction = scopeIterator;

            scopeIterator = scopeIterator.getScope();
        }
        if (scopeIterator == null) return List.of();

        List<CompletionItem> ret = new ArrayList<>();

        for (Symbol symbol : context.schemaIndex.listSymbolsInScope(scopeIterator, SymbolType.FUNCTION)) {
            if (symbol.equals(myFunction)) continue;
            ret.add(
                CompletionUtils.constructFunction(symbol.getShortIdentifier(), getFunctionSignature(symbol), symbol.getPrettyIdentifier())
            );
        }
        return ret;
    }

    List<List<FunctionSignature>> groupSignatures(List<FunctionSignature> functionSignatures) {
        List<List<FunctionSignature>> ret = new ArrayList<>();
        Set<Integer> skip = new HashSet<>();

        for (int i = 0; i < functionSignatures.size(); ++i) {
            if (skip.contains(i)) continue;

            FunctionSignature current = functionSignatures.get(i);
            if (current.getArgumentList().isEmpty()) {
                ret.add(List.of(current));
                continue;
            }
            if (!(current.getArgumentList().get(0) instanceof KeywordArgument)) {
                ret.add(List.of(current));
                continue;
            }

            List<FunctionSignature> group = new ArrayList<>() {{ add(current); }};

            for (int j = i + 1; j < functionSignatures.size(); ++j) {
                FunctionSignature candidate = functionSignatures.get(j);
                if (candidate.getArgumentList().size() != current.getArgumentList().size()) continue;
                if (!(candidate.getArgumentList().get(0) instanceof KeywordArgument)) continue;
                skip.add(j);
                group.add(candidate);
            }
            ret.add(group);
        }

        return ret;
    }

    List<CompletionItem> getBuiltinFunctions(EventCompletionContext context, SchemaNode node) {
        List<CompletionItem> ret = new ArrayList<>();
        Set<String> addedNames = new HashSet<>();
        for (var entry : BuiltInFunctions.rankExpressionBuiltInFunctions.entrySet()) {
            if (!(entry.getValue() instanceof GenericFunction)) continue;
            GenericFunction function = (GenericFunction)entry.getValue();
            String name = entry.getKey();

            for (List<FunctionSignature> group : groupSignatures(function.getSignatures())) {
                StringBuilder signatureStr = new StringBuilder("(");
                signatureStr.append(String.join(", ", group.get(0).getArgumentList().stream().map(arg -> arg.displayString()).toList()));
                signatureStr.append(")");
                CompletionItem item = CompletionUtils.constructFunction(name, signatureStr.toString(), "builtin");
                if (group.size() > 1) {
                    // first argument keyword
                    signatureStr = new StringBuilder()
                        .append(name)
                        .append("(${1|")
                        .append(String.join(",", group.stream().map(signature -> 
                                ((KeywordArgument)(signature.getArgumentList().get(0))).getArgument()).toList()))
                        .append("|}");

                    for (int i = 1; i < group.get(0).getArgumentList().size(); ++i) {
                        signatureStr.append(", ${")
                                    .append(i+1)
                                    .append(":")
                                    .append(group.get(0).getArgumentList().get(i).displayString())
                                    .append("}");
                    }
                    signatureStr.append(")");
                    item.setInsertText(signatureStr.toString());
                }
                ret.add(item);
            }
            addedNames.add(name);
        }

        for (String function : BuiltInFunctions.simpleBuiltInFunctionsSet) {
            if (addedNames.contains(function))continue;
            ret.add(CompletionUtils.constructFunction(function, "()", "builtin"));
        }

        return ret;
    }

    List<CompletionItem> getFunctionPropertyCompletion(EventCompletionContext context, SchemaNode startOfWordNode) {
        if (context.triggerCharacter != '.') return List.of();
        SchemaNode featureNode = CSTUtils.findASTClassAncestor(startOfWordNode, feature.class);
        if (featureNode == null || featureNode.size() == 0) {
            return List.of();
        }
        if (featureNode.getRankNode().isEmpty()) {
            return List.of();
        }

        RankNode rankNode = featureNode.getRankNode().get();

        Optional<SpecificFunction> functionSignature = rankNode.getBuiltInFunctionSignature();
        if (functionSignature.isEmpty()) {
            return List.of();
        }

        FunctionSignature signature = functionSignature.get().getSignature();

        List<CompletionItem> result = new ArrayList<>();

        for (String prop : signature.getProperties()) {
            if (prop.isBlank()) continue;
            result.add(CompletionUtils.constructBasic(prop));
        }
        return result;
    }

	@Override
	public List<CompletionItem> getCompletionItems(EventCompletionContext context) {
        SchemaNode clean = CSTUtils.getLastCleanNode(context.document.getRootNode(), context.position);

        List<CompletionItem> result = new ArrayList<>();
        if (matchFunctionCompletion(context,clean)) {

            result.addAll(getUserDefinedFunctions(context, clean));
            result.addAll(getBuiltinFunctions(context, clean));

        } else {
            result.addAll(getFunctionPropertyCompletion(context, CSTUtils.getNodeAtPosition(context.document.getRootNode(), context.startOfWord())));
        }
        return result;
	}
}
