//
// Copyright 2020-2021  SenX S.A.S.
//
// Use of this software is governed by the Business Source License
// included in the file licenses/BSL.txt.
//
// As of the Change Date specified in that file, in accordance with
// the Business Source License, use of this software will be governed
// by the Apache License, Version 2.0, included in the file
// licenses/APL.txt.
//

package io.warp10.ext.flows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;
import org.apache.commons.lang3.StringUtils;

import io.warp10.ext.flows.antlr.FLoWSExecutor;
import io.warp10.ext.flows.antlr.FLoWSLexer;
import io.warp10.ext.flows.antlr.FLoWSParser;
import io.warp10.ext.flows.antlr.FLoWSParser.BlockStatementsContext;
import io.warp10.quasar.token.thrift.data.TokenType;
import io.warp10.script.MemoryWarpScriptStack;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

public class FLOWS extends NamedWarpScriptFunction implements WarpScriptStackFunction {

  private final boolean eval;

  private static final long[] SIPHASH = { 0x42L, 0x123L };

  public FLOWS(String name, boolean eval) {
    super(name);
    this.eval = eval;
  }

  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    Object top = stack.pop();

    if (!(top instanceof String)) {
      throw new WarpScriptException(getName() + " operates on a STRING.");
    }

    String code = (String) top;

    //
    // Parse and generate
    //

    try {
      byte[] bytes = code.getBytes(StandardCharsets.UTF_8);

      ANTLRErrorListener listener = new ANTLRErrorListener() {
        @Override
        public void reportAmbiguity(Parser recognizer, DFA dfa, int startIndex, int stopIndex, boolean exact, BitSet ambigAlts, ATNConfigSet configs) {
        }
        @Override
        public void reportAttemptingFullContext(Parser recognizer, DFA dfa, int startIndex, int stopIndex, BitSet conflictingAlts, ATNConfigSet configs) {
        }
        @Override
        public void reportContextSensitivity(Parser recognizer, DFA dfa, int startIndex, int stopIndex, int prediction, ATNConfigSet configs) {
        }
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
          throw new RuntimeException("Syntax Error line " + line + ":" + charPositionInLine + ", " + msg);
        }
      };

      ByteArrayInputStream in = new ByteArrayInputStream(bytes);
      CharStream input = CharStreams.fromStream(in);
      FLoWSLexer lexer = new FLoWSLexer(input);
      lexer.addErrorListener(listener);
      CommonTokenStream tokens = new CommonTokenStream(lexer);
      FLoWSParser parser = new FLoWSParser(tokens);
      parser.addErrorListener(listener);
      BlockStatementsContext context = parser.blockStatements();

      //
      // Check if we consumed the whole input, if not emit an errot.
      //

      if (Token.EOF != parser.getCurrentToken().getType()) {
        throw new WarpScriptException(getName() + " found extraneous content on line L" + parser.getCurrentToken().getLine() + ":" + parser.getCurrentToken().getCharPositionInLine() + " at '" + StringUtils.abbreviateMiddle(parser.getCurrentToken().getText(), "...", 32) + "'.");
      }

      FLoWSExecutor generator = new FLoWSExecutor(eval ? (MemoryWarpScriptStack) stack : null);
      generator.indent();
      generator.generate(context);
      if (!eval) {
        // Wrap the generated code inside of a macro
        code = WarpScriptStack.MACRO_START + "\n" + generator.getWarpScript() + "\n" + WarpScriptStack.MACRO_END;
        stack.push(code);
      }
    } catch (Throwable t) {
      throw new WarpScriptException(getName() + " encountered an error.", t);
    }

    return stack;
  }
}
