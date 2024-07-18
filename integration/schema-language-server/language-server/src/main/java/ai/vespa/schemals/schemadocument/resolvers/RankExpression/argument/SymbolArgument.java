package ai.vespa.schemals.schemadocument.resolvers.RankExpression.argument;

import java.util.Optional;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import ai.vespa.schemals.context.ParseContext;
import ai.vespa.schemals.index.Symbol;
import ai.vespa.schemals.index.Symbol.SymbolStatus;
import ai.vespa.schemals.index.Symbol.SymbolType;
import ai.vespa.schemals.tree.SchemaNode;

public class SymbolArgument implements Argument {
    
    private SymbolType symbolType;

    public SymbolArgument(SymbolType symbolType) {
        this.symbolType = symbolType;
    }

    @Override
    public int getStrictness() {
        return 2;
    }

    private SchemaNode findSymbolNode(SchemaNode node) {
        SchemaNode symbolNode = node;

        while (!symbolNode.hasSymbol() && symbolNode.size() > 0) {
            symbolNode = symbolNode.get(0);
        }

        return symbolNode;
    }

    @Override
    public boolean validateArgument(SchemaNode node) {
        SchemaNode symbolNode = findSymbolNode(node);
        return symbolNode.hasSymbol();
    }

    @Override
    public Optional<Diagnostic> parseArgument(ParseContext context, SchemaNode node) {

        SchemaNode symbolNode = findSymbolNode(node);

        if (symbolNode.hasSymbol()) {
            Symbol symbol = symbolNode.getSymbol();

            if (symbol.getStatus() == SymbolStatus.REFERENCE) {
                symbol.setStatus(SymbolStatus.UNRESOLVED);
                context.schemaIndex().deleteSymbolReference(symbol);
            }
            
            if (symbol.getStatus() == SymbolStatus.UNRESOLVED) {
                symbol.setType(symbolType);
            }
        } else {
            return Optional.of(new Diagnostic(node.getRange(), "The argument must be to a symbol of type: " + symbolType, DiagnosticSeverity.Error, ""));
        }

        return Optional.empty();
    }

    public String displayString() {
        if (symbolType == SymbolType.FIELD) {
            return "name";
        }
        return symbolType.toString();
    }
}