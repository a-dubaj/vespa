package ai.vespa.schemals.lsp.hover;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;

import ai.vespa.schemals.context.EventPositionContext;
import ai.vespa.schemals.index.Symbol;
import ai.vespa.schemals.index.Symbol.SymbolStatus;
import ai.vespa.schemals.index.Symbol.SymbolType;
import ai.vespa.schemals.parser.indexinglanguage.ast.ATTRIBUTE;
import ai.vespa.schemals.parser.indexinglanguage.ast.INDEX;
import ai.vespa.schemals.parser.indexinglanguage.ast.SUMMARY;
import ai.vespa.schemals.tree.CSTUtils;
import ai.vespa.schemals.tree.SchemaNode;
import ai.vespa.schemals.tree.SchemaNode.LanguageType;

public class SchemaHover {
    private static final String markdownPathRoot = "hover/";

    private static Hover getStructHover(SchemaNode node, EventPositionContext context) {

        Optional<Symbol> structDefinitionSymbol = context.schemaIndex.getSymbolDefinition(node.getSymbol());
        
        if (structDefinitionSymbol.isEmpty()) {
            return null;
        }
        List<Symbol> fieldDefs = context.schemaIndex.listSymbolsInScope(structDefinitionSymbol.get(), SymbolType.FIELD);

        String result = "### struct " + structDefinitionSymbol.get().getShortIdentifier() + "\n";
        for (Symbol fieldDef : fieldDefs) {
            result += "    field " + fieldDef.getShortIdentifier() + " type " +  
                fieldDef.getNode().getNextSibling().getNextSibling().getText();

            if (!fieldDef.isInScope(structDefinitionSymbol.get())) {
                result += " (inherited from " + fieldDef.getScopeIdentifier() + ")";
            }

            result += "\n";
        }

        if (result.isEmpty())result = "no fields found";

        return new Hover(new MarkupContent(MarkupKind.MARKDOWN, result));
    }

    private static Hover getFieldHover(SchemaNode node, EventPositionContext context) {
        Optional<Symbol> fieldDefinitionSymbol = context.schemaIndex.getSymbolDefinition(node.getSymbol());

        if (fieldDefinitionSymbol.isEmpty()) return null;

        Optional<SchemaNode> dataTypeNode = context.schemaIndex.fieldIndex().getFieldDataTypeNode(fieldDefinitionSymbol.get());
        
        String typeText = "unknown";
        if (dataTypeNode.isPresent()) {
            typeText = dataTypeNode.get().getText().trim();
        }


        String hoverText = "field " + fieldDefinitionSymbol.get().getShortIdentifier() + " type " + typeText;

        if (context.schemaIndex.fieldIndex().getIsInsideDoc(fieldDefinitionSymbol.get())) {
            hoverText = "document " + hoverText;
        } else {
            hoverText = "schema " + hoverText;
        }

        var indexingTypes = context.schemaIndex.fieldIndex().getFieldIndexingTypes(fieldDefinitionSymbol.get()).toArray();
        for (int i = 0; i < indexingTypes.length; ++i) {
            if (i == 0) hoverText += "\n\t";
            else hoverText += ", ";
            hoverText += indexingTypes[i].toString().toLowerCase();
        }

        return new Hover(new MarkupContent(MarkupKind.PLAINTEXT, hoverText));
    }

    private static Hover getSymbolHover(SchemaNode node, EventPositionContext context) {
        switch(node.getSymbol().getType()) {
            case STRUCT:
                return getStructHover(node, context);
            case FIELD:
                return getFieldHover(node, context);
            default:
                break;
        }

        if (node.getSymbol().getType() == SymbolType.LABEL) {
            return new Hover(new MarkupContent(MarkupKind.PLAINTEXT, "label"));
        }

        if (node.getSymbol().getStatus() == SymbolStatus.BUILTIN_REFERENCE) {
            return new Hover(new MarkupContent(MarkupKind.PLAINTEXT, "builtin"));
        }

        if (node.getSymbol().getStatus() == SymbolStatus.REFERENCE || node.getSymbol().getStatus() == SymbolStatus.DEFINITION) {
            Optional<Symbol> definition = context.schemaIndex.getSymbolDefinition(node.getSymbol());

            if (definition.isPresent()) {
                SchemaNode defNode = definition.get().getNode();

                if (defNode.getParent() != null) {
                    String text = defNode.getParent().getText();
                    String[] lines = text.split(System.lineSeparator());
                    String hoverContent = lines[0].trim();
                    while (hoverContent.length() > 0 && hoverContent.endsWith("{")) {
                        hoverContent = hoverContent.substring(0, hoverContent.length() - 1);
                    }
                    hoverContent = hoverContent.trim();
                    if (hoverContent.length() > 0) {
                        return new Hover(new MarkupContent(MarkupKind.PLAINTEXT, hoverContent));
                    }
                }
            }
        }

        return null;
    }

    private static Hover getIndexingHover(SchemaNode hoverNode, EventPositionContext context) {
        // Note: these classes belong to the indexinglanguage parser
        if (hoverNode.isASTInstance(ATTRIBUTE.class)) {
            return new Hover(new MarkupContent(MarkupKind.MARKDOWN, 
                "## Attribute\n"
            +   "Attribute is used to make a field available for sorting, grouping, ranking and searching using match mode `word`.\n"
            ));
        } else if (hoverNode.isASTInstance(INDEX.class)) {
            return new Hover(new MarkupContent(MarkupKind.MARKDOWN, 
                "## Index\n"
            +   "Creates a searchable index for the values of this field using match mode `text`. "
            +   "All strings are lower-cased before stored in the index. "
            +   "By default, the index name will be the same as the name of the schema field. "
            +   "Use a fieldset to combine fields in the same set for searching.\n"
            ));
        } else if (hoverNode.isASTInstance(SUMMARY.class)) {
            return new Hover(new MarkupContent(MarkupKind.MARKDOWN, 
                "## Summary\n"
            +   "Includes the value of this field in a summary field. "
            +   "Summary fields of type string are limited to 64 kB. "
            +   "If a larger string is stored, the indexer will issue a warning and truncate the value to 64 kB. "
            +   "Modify summary output by using `summary:` in the field body (e.g. to generate dynamic teasers).\n"
            ));
        }

        return null;
    }

    public static Hover getHover(EventPositionContext context) {
        SchemaNode node = CSTUtils.getSymbolAtPosition(context.document.getRootNode(), context.position);
        if (node != null) {
            return getSymbolHover(node, context);
        }

        node = CSTUtils.getLeafNodeAtPosition(context.document.getRootNode(), context.position);
        if (node == null) return null;

        if (node.getLanguageType() == LanguageType.INDEXING) {
            return getIndexingHover(node, context);
        }


        context.logger.println("Hover: " + node.getClassLeafIdentifierString());

        String fileName = markdownPathRoot + node.getClassLeafIdentifierString() + ".md";

        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName);

        if (inputStream == null) {
            return null;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        
        String markdown = reader.lines().collect(Collectors.joining(System.lineSeparator()));

        return new Hover(new MarkupContent(MarkupKind.MARKDOWN, markdown), node.getRange());
    }
}
