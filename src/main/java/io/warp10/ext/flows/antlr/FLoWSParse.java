//
// Copyright 2020  SenX S.A.S.
//
// Use of this software is governed by the Business Source License
// included in the file licenses/BSL.txt.
//
// As of the Change Date specified in that file, in accordance with
// the Business Source License, use of this software will be governed
// by the Apache License, Version 2.0, included in the file
// licenses/APL.txt.
//

package io.warp10.ext.flows.antlr;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import io.warp10.ext.flows.antlr.FLoWSParser.BlockStatementsContext;

public class FLoWSParse {
  public static void main(String[] args) throws Exception {
    CharStream input = CharStreams.fromStream(System.in);
    FLoWSLexer lexer = new FLoWSLexer(input);
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    FLoWSParser parser = new FLoWSParser(tokens);
    BlockStatementsContext context = parser.blockStatements();
    
    FLoWSExecutor generator = new FLoWSExecutor(null);
    
    generator.generate(context);
    
    System.out.println(generator.getWarpScript());
  }
}
