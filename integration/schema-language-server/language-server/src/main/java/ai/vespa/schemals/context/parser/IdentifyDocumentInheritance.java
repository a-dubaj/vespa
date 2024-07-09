package ai.vespa.schemals.context.parser;

import java.util.ArrayList;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import ai.vespa.schemals.context.ParseContext;
import ai.vespa.schemals.index.Symbol;
import ai.vespa.schemals.parser.ast.identifierStr;
import ai.vespa.schemals.parser.ast.inheritsDocument;
import ai.vespa.schemals.tree.SchemaNode;
import ai.vespa.schemals.tree.SymbolReferenceNode;

public class IdentifyDocumentInheritance extends Identifier {

	public IdentifyDocumentInheritance(ParseContext context) {
		super(context);
	}

	@Override
	public ArrayList<Diagnostic> identify(SchemaNode node) {
        ArrayList<Diagnostic> ret = new ArrayList<>();

        if (!node.isASTInstance(identifierStr.class)) return ret;
        if (node.getParent() == null || !node.getParent().isASTInstance(inheritsDocument.class)) return ret;

        if (!(node instanceof SymbolReferenceNode)) {
            ret.add(new Diagnostic(node.getRange(), "Should be reference", DiagnosticSeverity.Warning, ""));
        }

        this.context.addUnresolvedInheritanceNode(node);

        return ret;
	}
}
