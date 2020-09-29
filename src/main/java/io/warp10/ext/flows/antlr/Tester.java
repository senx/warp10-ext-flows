package io.warp10.ext.flows.antlr;

import java.io.File;
import java.io.IOException;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

public class Tester {
    private static void printPrettyLispTree(String tree) {
        int indentation = 1;
        for (char c : tree.toCharArray()) {
            if (c == '(') {
                if (indentation > 1) {
                    System.out.println();
                }
                for (int i = 0; i < indentation; i++) {
                    System.out.print("  ");
                }
                indentation++;
            }
            else if (c == ')') {
                indentation--;
            }
            System.out.print(c);
        }
        System.out.println();
    }
    public static void main(String[] args) throws IOException {
        String source = args[0];
        FLoWSLexer lexer = new File(source).exists() ?
                new FLoWSLexer(CharStreams.fromFileName(source)) :
                new FLoWSLexer(CharStreams.fromString(source));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        System.out.println("\n[TOKENS]");
        for (Token t : tokens.getTokens()) {
            String symbolicName = FLoWSLexer.VOCABULARY.getSymbolicName(t.getType());
            String literalName = FLoWSLexer.VOCABULARY.getLiteralName(t.getType());
            System.out.printf("  %-20s '%s'\n",
                    symbolicName == null ? literalName : symbolicName,
                    t.getText().replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t"));
        }
        System.out.println("\n[PARSE-TREE]");
        FLoWSParser parser = new FLoWSParser(tokens);
        ParserRuleContext context = parser.blockStatements();
        String tree = context.toStringTree(parser);
        printPrettyLispTree(tree);
    }
}
