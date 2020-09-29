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

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;

import io.warp10.WarpURLDecoder;
import io.warp10.ext.flows.FLOWSASSERTDEPTH;
import io.warp10.ext.flows.FLOWSENSUREDEPTH;
import io.warp10.ext.flows.FLoWSWarpScriptExtension;
import io.warp10.ext.flows.antlr.FLoWSParser.AnonMacroContext;
import io.warp10.ext.flows.antlr.FLoWSParser.ArgumentListContext;
import io.warp10.ext.flows.antlr.FLoWSParser.AssignmentContext;
import io.warp10.ext.flows.antlr.FLoWSParser.BangIdentifierContext;
import io.warp10.ext.flows.antlr.FLoWSParser.BlockStatementContext;
import io.warp10.ext.flows.antlr.FLoWSParser.BlockStatementsContext;
import io.warp10.ext.flows.antlr.FLoWSParser.CompositeElementContext;
import io.warp10.ext.flows.antlr.FLoWSParser.CompositeElementIndexContext;
import io.warp10.ext.flows.antlr.FLoWSParser.ExpressionContext;
import io.warp10.ext.flows.antlr.FLoWSParser.FuncCallContext;
import io.warp10.ext.flows.antlr.FLoWSParser.IdentifierContext;
import io.warp10.ext.flows.antlr.FLoWSParser.IdentifiersContext;
import io.warp10.ext.flows.antlr.FLoWSParser.ListContext;
import io.warp10.ext.flows.antlr.FLoWSParser.MacroBodyContext;
import io.warp10.ext.flows.antlr.FLoWSParser.MacroCallContext;
import io.warp10.ext.flows.antlr.FLoWSParser.MacroParametersContext;
import io.warp10.ext.flows.antlr.FLoWSParser.MapArgumentListContext;
import io.warp10.ext.flows.antlr.FLoWSParser.MapContext;
import io.warp10.ext.flows.antlr.FLoWSParser.MapEntryContext;
import io.warp10.ext.flows.antlr.FLoWSParser.ReturnValueContext;
import io.warp10.ext.flows.antlr.FLoWSParser.SingleValueContext;
import io.warp10.script.MemoryWarpScriptStack;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptLib;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStack.Macro;
import io.warp10.script.WarpScriptStackFunction;
import io.warp10.script.functions.CLEAR;
import io.warp10.script.functions.DROP;
import io.warp10.script.functions.DUP;
import io.warp10.script.functions.ENDLIST;
import io.warp10.script.functions.ENDMAP;
import io.warp10.script.functions.EVAL;
import io.warp10.script.functions.FORGET;
import io.warp10.script.functions.GET;
import io.warp10.script.functions.HIDE;
import io.warp10.script.functions.LOAD;
import io.warp10.script.functions.MARK;
import io.warp10.script.functions.PUT;
import io.warp10.script.functions.RESTORE;
import io.warp10.script.functions.RUN;
import io.warp10.script.functions.SAVE;
import io.warp10.script.functions.SHOW;
import io.warp10.script.functions.STORE;
import io.warp10.script.functions.SWAP;

public class FLoWSExecutor {
  
  private static final String LPAREN = "(";
  private static final String RPAREN = ")";
  
  private static final EVAL EVAL = new EVAL(WarpScriptLib.EVAL);
  private static final ENDMAP MAP_END = new ENDMAP(WarpScriptLib.MAP_END);
  private static final ENDLIST LIST_END = new ENDLIST(WarpScriptLib.LIST_END);
  private static final MARK MAP_START = new MARK(WarpScriptLib.MAP_START);
  private static final MARK LIST_START = new MARK(WarpScriptLib.LIST_START);
  private static final LOAD LOAD = new LOAD(WarpScriptLib.LOAD);
  private static final STORE STORE = new STORE(WarpScriptLib.STORE);
  private static final FORGET FORGET = new FORGET(WarpScriptLib.FORGET);
  private static final RUN RUN = new RUN(WarpScriptLib.RUN);
  private static final SAVE SAVE = new SAVE(WarpScriptLib.SAVE);
  private static final RESTORE RESTORE = new RESTORE(WarpScriptLib.RESTORE);
  private static final FLOWSASSERTDEPTH FLOWS_ASSERTDEPTH = new FLOWSASSERTDEPTH(FLoWSWarpScriptExtension.FLOWS_ASSERTDEPTH);
  private static final FLOWSENSUREDEPTH FLOWS_ENSUREDEPTH = new FLOWSENSUREDEPTH(FLoWSWarpScriptExtension.FLOWS_ENSUREDEPTH);
  private static final SWAP SWAP = new SWAP(WarpScriptLib.SWAP);
  private static final PUT PUT = new PUT(WarpScriptLib.PUT);
  private static final GET GET = new GET(WarpScriptLib.GET);
  private static final HIDE HIDE = new HIDE(WarpScriptLib.HIDE);
  private static final SHOW SHOW = new SHOW(WarpScriptLib.SHOW);
  private static final DUP DUP = new DUP(WarpScriptLib.DUP);
  private static final DROP DROP = new DROP(WarpScriptLib.DROP);
  private static final CLEAR CLEAR = new CLEAR(WarpScriptLib.CLEAR);
  
  public static class FLoWSException extends WarpScriptException {
    public FLoWSException(String msg, Throwable cause) {
      super(msg, cause);
    }
  }
  
  //
  // Those MUST be String even though they could be chars
  // because at some places we concatenate them with numbers
  // expecting a String
  //
  private static final String SPACE = " ";
  private static final String LF = "\n";
  
  private static final String INDENTATION = "                                          ";
  //private static final String INDENTATION = "##########################################";
  private int indent = 2;
  private boolean needIndent = true;
  
  /**
   * If set to true then stack depth will be checked so it matches
   * the needed number of elements, otherwise missing elements will
   * be replaced by NULL and extraneous elements will be cleared
   * after assignment.
   */
  private final boolean strictAssignments = true;  
  private final boolean useFLoWSFunctions = true;
  
  StringBuilder sb = new StringBuilder();
  
  /**
   * Index for generated variable names
   */
  private int varidx = 0;
  private int macroidx = 0;
  private int assignidx = 0;
  
  private int curindent = 0;
  
  private String prefix = "#";
  
  private boolean comments = true;

  private List<Macro> macros = new ArrayList<Macro>();
  
  /**
   * Current contexts
   */
  private List<ParserRuleContext> contexts = new ArrayList<ParserRuleContext>();
  
  /**
   * Set to true to eval the code instead of generating WarpScript
   */
  private final boolean eval;
  
  private final AtomicBoolean RETURN = new AtomicBoolean(false);
  
  private final MemoryWarpScriptStack stack;
   
  public FLoWSExecutor(MemoryWarpScriptStack stack) {
    this.stack = stack;
    this.eval = null != stack;
  }
  
  public String getWarpScript() {
    return sb.toString();
  }
  
  public void generate(Object context) throws WarpScriptException {
    ParserRuleContext prc = null;
    
    if (context instanceof ParserRuleContext) {
      prc = (ParserRuleContext) context;
      contexts.add(prc);
    } else {
      contexts.add(null);
    }
    
    try {      
      if (null == context) {
        return;
      }
      if (context instanceof BlockStatementsContext) {      
        genBlockStatements((BlockStatementsContext) context);
      } else if (context instanceof BlockStatementContext) {
        genBlockStatement((BlockStatementContext) context);
      } else if (context instanceof MacroCallContext) {
        genMacroCall(RETURN.getAndSet(false), (MacroCallContext) context);
      } else if (context instanceof FuncCallContext) {
        genFuncCall(RETURN.getAndSet(false), (FuncCallContext) context);
      } else if (context instanceof AssignmentContext) {
        genAssignment((AssignmentContext) context);
      } else if (context instanceof ArgumentListContext) {
        genArgumentList((ArgumentListContext) context);
      } else if (context instanceof MapArgumentListContext) {
        genMapArgumentList((MapArgumentListContext) context);
      } else if (context instanceof ExpressionContext) {
        genExpression((ExpressionContext) context);
      } else if (context instanceof SingleValueContext) {
        genSingleValue((SingleValueContext) context);        
      } else if (context instanceof IdentifierContext) {        
        genIdentifier(RETURN.getAndSet(false), (IdentifierContext) context);
      } else if (context instanceof BangIdentifierContext) {
        genIdentifier(true, RETURN.getAndSet(false), ((BangIdentifierContext) context).identifier());
      } else if (context instanceof AnonMacroContext) {
        genAnonMacro((AnonMacroContext) context);
      } else if (context instanceof MacroBodyContext) {
        genMacroBody((MacroBodyContext) context);
      } else if (context instanceof MacroParametersContext) {
        genMacroParameters((MacroParametersContext) context);
      } else if (context instanceof ListContext) {
        genList((ListContext) context);
      } else if (context instanceof MapContext) {
        genMap((MapContext) context);
      } else if (context instanceof CompositeElementIndexContext) {
        genCompositeElementIndex((CompositeElementIndexContext) context);
      } else if (context instanceof CompositeElementContext) {
        genCompositeElement((CompositeElementContext) context);
      } else if (context instanceof ReturnValueContext) {
        genReturnValue((ReturnValueContext) context);
      } else {
        //throw new RuntimeException("Unexpected context " + context.getClass() + " (" + context.toString() + ")");
      }
    } catch (FLoWSException fe) {
      throw fe;
    } catch (Exception e) {
      String pos = null == prc ? "" : "L" + prc.start.getLine() + ":" + prc.start.getCharPositionInLine() + "-" + prc.stop.getLine() + ":" + prc.stop.getCharPositionInLine();
      throw new FLoWSException("Error at FLoWS " + pos, e);
    } finally {
      contexts.remove(contexts.size() - 1);
    }
  }
  
  private void genCompositeElementIndex(CompositeElementIndexContext context) throws WarpScriptException {
    if (context.getChild(0) instanceof TerminalNodeImpl) {
      if (eval) {
        if (null != context.STRING()) {
          String s = context.STRING().getText();
          s = s.substring(1, s.length() - 1);
          try {
            push(WarpURLDecoder.decode(s, StandardCharsets.UTF_8));
          } catch (UnsupportedEncodingException uee) {
            // Can't happen since we use a standard charset
            throw new WarpScriptException("Error decoding STRING.", uee);
          }          
        } else if (null != context.LONG()) {
          push(Long.parseLong(context.LONG().getText()));
        }
      } else {
        sb.append(indentation() + context.getChild(0).getText() + SPACE);
      }
    } else {
      generate(context.getChild(0));
    }    
  }
  
  private void genCompositeElement(CompositeElementContext context) throws WarpScriptException {
    String stacksymbol = genSymbol();
    saveStack(stacksymbol);
    indent();
    if (eval) {
      generate(context.getChild(0));
      generate(context.getChild(2));
      assertDepth(2L);
      apply(GET);
    } else {
      sb.append(indentation());
      generate(context.getChild(0));
      sb.append(indentation());
      generate(context.getChild(2));
      assertDepth(2L);
      sb.append(indentation() + WarpScriptLib.GET);
      sb.append(LF());      
    }
    deindent();
    restoreStack(stacksymbol);
  }
    
  private void genMap(MapContext context) throws WarpScriptException {
    if (eval) {
      apply(MAP_START);
      generate(context.mapArgumentList());
      apply(MAP_END);
    } else {
      sb.append(indentation() + WarpScriptLib.MAP_START);
      sb.append(LF());
      indent();
      generate(context.mapArgumentList());
      deindent();
      sb.append(indentation() + WarpScriptLib.MAP_END);
      sb.append(LF());      
    }
  }
    
  private void genList(ListContext context) throws WarpScriptException {
    if (eval) {
      apply(LIST_START);
      generate(context.argumentList());
      apply(LIST_END);
    } else {
      sb.append(indentation() + WarpScriptLib.LIST_START);
      sb.append(LF());
      indent();
      generate(context.argumentList());
      deindent();
      sb.append(indentation() + WarpScriptLib.LIST_END);
      sb.append(LF());      
    }
  }
  
  private void genMacroParameters(MacroParametersContext context) throws WarpScriptException {
    if (null == context.parameterList()) {
      if (eval) {
        push(0L);
        apply(FLOWS_ASSERTDEPTH);
      } else {
        if (comments) {
          sb.append(indentation() + "// Parameterless macro");
          sb.append(LF());
        }
        sb.append(indentation() + "0" + SPACE + FLoWSWarpScriptExtension.FLOWS_ASSERTDEPTH);
        sb.append(LF());        
      }
      return;
    }
    
    List<IdentifierContext> ids = context.parameterList().identifier();
    
    if (eval) {
      assertDepth(ids.size());
      List<String> vars = new ArrayList<String>(ids.size());
      for (int i = 0; i < ids.size(); i++) {
        vars.add(ids.get(i).getText());
      }
      push(vars);
      apply(STORE);
    } else {
      if (comments) {
        sb.append(indentation() + "// Storing macro parameters");
        sb.append(LF());
      }
      // Add a check to ensure we have just the right number of elements on the stack
      assertDepth(ids.size());
      sb.append(LF());
      sb.append(indentation() + WarpScriptLib.LIST_START);
      sb.append(SPACE);
      for (int i = 0; i < ids.size(); i++) {
        sb.append("'" + ids.get(i).getText() + "' ");
      }
      sb.append(WarpScriptLib.LIST_END);
      sb.append(SPACE);
      sb.append(WarpScriptLib.STORE);
      sb.append(LF());      
    }
  }
  
  private void genMacroBody(MacroBodyContext context) throws WarpScriptException {
    generate(context.blockStatements());
  }
  
  private void genAnonMacro(AnonMacroContext context) throws WarpScriptException {
    int index = macroidx++;
    String symbol = genSymbol();
    
    macros.add(new Macro());

    boolean saveContext = null != context.ARROW();

    if (eval) {
      if (saveContext) {
        apply(SAVE);
        push(symbol);
        apply(STORE);
      }
    } else {
      sb.append(indentation() + WarpScriptStack.MACRO_START);
      if (comments) {
        sb.append(SPACE + "// BEGIN Macro definition #" + index);
      }    
      sb.append(LF());
      indent();
      section(context);
      // Save the context
      if (saveContext) {
        sb.append(indentation());
        sb.append(WarpScriptLib.SAVE);
        sb.append(" '" + symbol + "' ");
        sb.append(WarpScriptLib.STORE);
        sb.append(LF());
        indent();
      }
    }
    
    // Store the parameters
    generate(context.macroParameters());
      
    // nresults is the number of results which must be checked
    Long nresults = null == context.LONG() ? null : Long.parseLong(context.LONG().getText());
    
    String stacksymbol = genSymbol();
      
    if (null != nresults) {
      if (nresults < 0) {
        throw new WarpScriptException("Number of return parameters cannot be negative.");
      }
      saveStack(stacksymbol);
      indent();
    }
      
    // Emit the macro body
    generate(context.macroBody());
      
    if (null != nresults) {
      assertDepth(nresults.intValue());
      deindent();
      restoreStack(stacksymbol);
    }
      
    // Restore the context
    
    if (eval) {
      if (saveContext) {
        push(symbol);
        apply(LOAD);
        apply(RESTORE);
      }
      Macro macro = macros.remove(macros.size() - 1);
      macro.setName("FLoWS L" + context.start.getLine() + ":" + context.start.getCharPositionInLine() + "-" + context.stop.getLine() + ":" + context.stop.getCharPositionInLine());
      // TODO(hbs): set TTL/Name? 
      push(macro);
    } else {
      if (saveContext) {
        deindent();
        sb.append(indentation() + "'" + symbol + "' " + WarpScriptLib.LOAD + SPACE);
        sb.append(WarpScriptLib.RESTORE);
        sb.append(LF());
      }
      deindent();
      sb.append(indentation() + WarpScriptStack.MACRO_END);
      if (comments) {
        sb.append(SPACE + "// END Macro definition #" + index);
      }
      sb.append(LF());      
    }    
  }
  
  private void genSingleValue(SingleValueContext context) throws WarpScriptException {
    generate(context.getChild(0));
  }
  
  private void genExpression(ExpressionContext context) throws WarpScriptException {
    if (context.getChildCount() >= 2 && LPAREN.equals(context.getChild(0).getText()) && RPAREN.equals(context.getChild(context.getChildCount() - 1).getText())) {
      generate(context.expression(0));
    } else if (3 == context.getChildCount()) {
      // expr OP expr
      String symbol = genSymbol();
      saveStack(symbol);
      indent();
      generate(context.expression(0));
      generate(context.expression(1));
      assertDepth(2);
      eval(context.getChild(1).getText());
      deindent();
      restoreStack(symbol);
    } else {
      if (context.children.get(0) instanceof TerminalNodeImpl) {
        String node = context.children.get(0).getText();
        
        if (eval) {
          if (null != context.BOOLEAN()) {
            push("true".equals(node));
          } else if (null != context.STRING()) {
            try {
              String s = WarpURLDecoder.decode(node.substring(1, node.length() - 1), StandardCharsets.UTF_8);          
              push(s);
            } catch (UnsupportedEncodingException uee) {
              // Can't happen since we use a standard charset
              throw new WarpScriptException("Error decoding STRING.", uee);
            }
          } else if (null != context.LONG()) {
            if (node.startsWith("0x")) {
              push(node.length() < 18 ? Long.parseLong(node.substring(2), 16) : new BigInteger(node.substring(2), 16).longValue());
            } else {
              push(Long.parseLong(node));
            }
          } else if (null != context.DOUBLE()) {
            push(Double.parseDouble(node));
          }
        } else {
          if ((node.startsWith("'") || node.startsWith("\"")) && node.contains("\n")) {
            node = node.replaceAll("\n", "%0A");
          }      
          sb.append(indentation() + node);
          sb.append(LF());        
        }
      } else {
        generate(context.children.get(0));
      }      
    }    
  }
/*  
  private void genNumLong(NumLongContext context) throws WarpScriptException {
    if (eval) {
      push(Long.parseLong(context.getText()));
    } else {
      sb.append(indentation() + context.getText());
      sb.append(LF());
    }
  }
  
  private void genNumDouble(NumDoubleContext context) throws WarpScriptException {
    if (eval) {
      push(Double.parseDouble(context.getText()));
    } else {
      sb.append(indentation() + context.getText());
      sb.append(LF());
    }
  }
*/  
  private void genBlockStatements(BlockStatementsContext context) throws WarpScriptException {
    if (null != context.blockStatement()) {
      boolean returned = false;
      for (BlockStatementContext block: context.blockStatement()) {        
        if (returned) {
          throw new WarpScriptException("Extraneous statement after block return.");
        }
        generate(block);
        returned = null != block.returnValue();
      }      
    }
  }

  private void genReturnValue(ReturnValueContext context) throws WarpScriptException {
    RETURN.set(true);
    
    if (null != context.expression()) {
      generate(context.expression());
    } else if (null != context.funcCall()) {
      generate(context.funcCall());
    } else if (null != context.macroCall()) {
      generate(context.macroCall());
    } else if (null != context.returnValue()) {
      for (ReturnValueContext rvc: context.returnValue()) {
        genReturnValue(rvc);
      }
    }
  }
  
  private void genBlockStatement(BlockStatementContext context) throws WarpScriptException {
    RETURN.set(null != context.RETURN());

    if (null != context.funcCall()) {
      generate(context.funcCall());
    } else if (null != context.macroCall()) {
      generate(context.macroCall());
    } else if (null != context.assignment()) {
      generate(context.assignment());
    } else if (null != context.returnValue()) {
      generate(context.returnValue());
    } else {
      throw new WarpScriptException("Invalid block statement.");
    }    
  }
  
  private void genArgumentList(ArgumentListContext context) throws WarpScriptException {
    if (null != context.expression()) {
      for (ExpressionContext expr: context.expression()) {
        if (eval) {
          generate(expr);
        } else {
          sb.append(indentation());
          generate(expr);
          sb.append(LF());          
        }
      }      
    }
  }

  private void genMapArgumentList(MapArgumentListContext context) throws WarpScriptException {
    if (null != context.mapEntry()) {
      for (MapEntryContext entry: context.mapEntry()) {
        if (eval) {
          generate(entry.expression(0));
          generate(entry.expression(1));
        } else {
          sb.append(indentation());
          generate(entry.expression(0));
          generate(entry.expression(1));
          sb.append(LF());          
        }
      }
    }
  }
  
  private void genIdentifier(boolean ret, IdentifierContext context) throws WarpScriptException {
    genIdentifier(false, ret, context);
  }
  
  private void genIdentifier(boolean bang, boolean ret, IdentifierContext context) throws WarpScriptException {
    boolean inBlockStatement = context.getParent() instanceof BlockStatementContext;

    // Ignore identifiers without a 'return' in block statement
    if (inBlockStatement && !ret) {
      return;
    }
    
    if (eval) {      
      if (bang || macros.isEmpty()) {
        Object val = stack.load(context.getText());
        if (null == val && !stack.getSymbolTable().containsKey(context.getText())) {
          throw new WarpScriptException("Unknown variable '" + context.getText() + "'.");          
        }
        push(val);
      } else {
        push(context.getText());
        apply(LOAD);
      }
    } else {
      sb.append(indentation() + (bang ? "!" : "") + "$" + context.getText() + SPACE);
      sb.append(LF());      
    }
  }
  
  private void genIdentifiers(IdentifiersContext context) throws WarpScriptException {
    if (null != context.identifier()) {
      for (IdentifierContext ctx: context.identifier()) {
        generate(ctx);
      }      
    }
  }
  
  private void genMacroCall(boolean ret, MacroCallContext context) throws WarpScriptException {
    boolean singleValue = context.getParent() instanceof SingleValueContext;
    boolean inBlockStatement = context.getParent() instanceof BlockStatementContext;
    
    String symbol = genSymbol();
    
    if (!eval && comments) {
      sb.append(LF());
      sb.append(indentation() + "// @" + context.getChild(1).getText() + "(...)");
    }
    if (!eval) {
      sb.append(LF());
      section(context);
    }
    
    saveStack(symbol);
    
    if (!eval) {
      indent();
    }
    
    // Generate code for arguments
    generate(context.getChild(3)); // '@' MACRO '(' ... ')'
    
    if (eval) {
      push(context.getChild(1).getText());
      apply(RUN);
    } else {
      // Extract macro name    
      sb.append(indentation() + "@" + context.getChild(1).getText());
      sb.append(LF());      
    }
    
    if (singleValue) {
      // Make sure we have one return value which can be used as the value of the expression
      if (strictAssignments) {
        assertDepth(1);
      } else {
        ensureDepth(1);
      }
    }
    
    //
    // When called in a block statement, the return values are ignored if not preceded by 'return'
    //
    
    if (inBlockStatement && !ret) {
      if (eval) {
        apply(CLEAR);
      } else {
        sb.append(indentation() + WarpScriptLib.CLEAR);
        sb.append(LF());
      }
    }

    // We restore the stack prior to the macro call
    if (!eval) {
      sb.append(LF());
      deindent();
    }
    restoreStack(symbol);
    if (!eval) {
      sb.append(LF());
    }
  }
  
  private void genFuncCall(boolean ret, FuncCallContext context) throws WarpScriptException {
    boolean singleValue = context.getParent() instanceof SingleValueContext;
    boolean inBlockStatement = context.getParent() instanceof BlockStatementContext;

    String symbol = genSymbol();
    if (!eval && comments) {
      sb.append(indentation() + "// " + context.getChild(0).getText() + "(...)");
      sb.append(LF());
    }
    if (!eval) {
      section(context);
    }
    saveStack(symbol);
    
    if (!eval) {
      indent();
    }

    // Generate code for arguments
    generate(context.getChild(2)); // F '(' ... ')'
    
    // Generate function call
    if (eval) {
      //
      // Check WarpScript functions
      //

      String stmt = context.getChild(0).getText();
      Object func = stack.getDefined().get(stmt);

      if (null != func && Boolean.FALSE.equals(stack.getAttribute(WarpScriptStack.ATTRIBUTE_ALLOW_REDEFINED))) {
        throw new WarpScriptException("Disallowed redefined function '" + stmt + "'.");
      }

      func = null != func ? func : WarpScriptLib.getFunction(stmt);

      if (null == func) {
        throw new WarpScriptException("Unknown function '" + stmt + "'");
      }

      if (func instanceof WarpScriptStackFunction && macros.isEmpty()) {
        //
        // Function is an WarpScriptStackFunction, call it on this stack
        //

        WarpScriptStackFunction esf = (WarpScriptStackFunction) func;
        apply(esf);
      } else {
        //
        // Push any other type of function onto the stack
        //
        push(func);
      }
    } else {
      sb.append(indentation() + context.getChild(0).getText());
      sb.append(LF());
    }
    
    if (singleValue) {
      // Make sure we have one return value which can be used as the value of the expression
      if (strictAssignments) {
        assertDepth(1);
      } else {
        ensureDepth(1);
      }
    }

    //
    // When called in a block statement, the return values are ignored if not preceded by 'return'
    //
    
    if (inBlockStatement && !ret) {
      if (eval) {
        apply(CLEAR);
      } else {
        sb.append(indentation() + WarpScriptLib.CLEAR);
        sb.append(LF());
      }
    }

    // We restore the stack prior to the function call
    if (!eval) {
      sb.append(LF());
      deindent();
    }
    
    restoreStack(symbol);
    if (!eval) {
      sb.append(LF());
    }
  }
  
  private void genAssignment(AssignmentContext context) throws WarpScriptException {    
    int index = assignidx++;
    
    String symbol = genSymbol();

    if (!eval && comments) {
      sb.append(LF() + indentation() + "// Assignment #" + index);
    }
    if (!eval) {
      sb.append(LF());
      section(context);
    }
    
    saveStack(symbol);
    
    if (!eval) {
      indent();
    }
    
    // Emit the value that will be assigned
    generate(context.getChild(context.getChildCount() - 1));
        
    if (null != context.compositeElementIndex() && context.compositeElementIndex().size() > 0) {
      if (strictAssignments) {
        assertDepth(1);
      } else {
        // Add null if the stack is empty, remove elements if there are too many
        ensureDepth(1);
      }

      String sym = genSymbol();
      saveStack(sym);
      indent();
      
      if (eval) {
        if (macros.isEmpty()) {
          push(stack.load(context.identifier().getText()));
        } else {
          push(context.identifier().getText());
          apply(LOAD);
        }
      } else {
        sb.append(indentation() + "$" + context.identifier().getText());
        sb.append(LF());
      }
      
      for (int i = 0; i < context.compositeElementIndex().size() - 1; i++) {        
        generate(context.compositeElementIndex(i));
        assertDepth(2);
        if (eval) {
          apply(GET);
        } else {
          sb.append(indentation() + WarpScriptLib.GET);
          sb.append(LF());
        }
      }
      
      deindent();
      restoreStack(sym);
      
      if (eval) {
        if (macros.isEmpty()) {
          stack.swap();
        } else {
          apply(SWAP);
        }
      } else {
        sb.append(indentation() + WarpScriptLib.SWAP);
        sb.append(LF());
      }
      
      generate(context.compositeElementIndex(context.compositeElementIndex().size() - 1));

      // We need 3 elements, the target, the value and the key at which to PUT 
      assertDepth(3);

      if (eval) {
        apply(PUT);
        apply(DROP);
      } else {
        sb.append(indentation()+ WarpScriptLib.PUT + SPACE + WarpScriptLib.DROP);
        sb.append(LF());
      }
    } else if (null != context.identifier()) {
      if (strictAssignments) {
        assertDepth(1);
      } else {
        // Add null if the stack is empty, remove elements if there are too many
        ensureDepth(1);
      }
      if (eval) {
        push(context.identifier().getText());
        apply(STORE);
      } else {
        sb.append(indentation() + "'" + context.identifier().getText() + "' ");
        sb.append(WarpScriptLib.STORE);
        sb.append(LF());        
      }
    } else if (null != context.identifiers()) {
      int count = context.identifiers().identifier().size();
      if (strictAssignments) {
        assertDepth(count);
      } else {
        // Adapt the number of elements returned on the stack
        // If there are not enough elements, add NULLs
        // If there are too many elements, drop some
        ensureDepth(count);
      }
      
      List<IdentifierContext> ids = context.identifiers().identifier();

      if (eval) {
        List<String> vars = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
          vars.add(ids.get(i).getText());
        }
        push(vars);
        apply(STORE);
      } else {
        sb.append(indentation() + WarpScriptLib.LIST_START);
        sb.append(SPACE);
        for (int i = 0; i < count; i++) {
          sb.append("'" + ids.get(i).getText() + "' ");
        }
        sb.append(WarpScriptLib.LIST_END);
        sb.append(SPACE);
        sb.append(WarpScriptLib.STORE);
        
        sb.append(LF());        
      }
    }
    if (!eval) {
      deindent();
    }
    restoreStack(symbol);
  }
  
  /**
   * Emits WarpScript code to save the content of the stack in a variable
   * @param symbol Symbol name to use for storing the stack
   */
  private void saveStack(String symbol) throws WarpScriptException {
    if (eval) {
      // We want to hide everything
      push(null);
      apply(HIDE);
      push(symbol);
      apply(STORE);
    } else {
      sb.append(indentation() + WarpScriptLib.NULL + SPACE + WarpScriptLib.HIDE + SPACE + "'" + symbol + "'" + SPACE + WarpScriptLib.STORE);
      sb.append(LF());      
    }
  }
  
  private void restoreStack(String symbol) throws WarpScriptException {
    if (eval) {
      if (!macros.isEmpty()) {
        push(symbol);
        apply(LOAD);
        apply(SHOW);
      } else {
        push(symbol);
        apply(DUP);
        apply(LOAD);
        apply(SHOW);
        apply(FORGET);
      }
      // Check op count whenever the stack is restored
      stack.checkOps();
    } else {
      if (!macros.isEmpty()) {
        sb.append(indentation() + "'" + symbol + "'" + SPACE + WarpScriptLib.LOAD + SPACE + WarpScriptLib.SHOW);
      } else {
        sb.append(indentation() + "'" + symbol + "'" + SPACE + WarpScriptLib.DUP + SPACE + WarpScriptLib.LOAD + SPACE + WarpScriptLib.SHOW + SPACE + WarpScriptLib.FORGET);
      }
      sb.append(LF());      
    }
  }
  
  private void assertDepth(long count) throws WarpScriptException {
    if (eval) {
      push(count);
      apply(FLOWS_ASSERTDEPTH);
    } else {
      if (useFLoWSFunctions) {
        sb.append(indentation() + count + SPACE + FLoWSWarpScriptExtension.FLOWS_ASSERTDEPTH);
      } else {
        sb.append(indentation() + WarpScriptLib.DEPTH);
        sb.append(SPACE + count + " == 'Invalid number of values, expected " + count + "' ");
        sb.append(WarpScriptLib.ASSERTMSG);
      }
      sb.append(LF());            
    }
  }
  
  private void ensureDepth(long count) throws WarpScriptException {
    if (eval) {
      push(count);
      apply(FLOWS_ENSUREDEPTH);
    } else {
      if (useFLoWSFunctions) {
        sb.append(indentation() + count + SPACE + FLoWSWarpScriptExtension.FLOWS_ENSUREDEPTH);
      } else {
        sb.append(indentation() + WarpScriptLib.DEPTH);
        sb.append(SPACE + count + " < ");
        sb.append(WarpScriptStack.MACRO_START);
        sb.append(SPACE);
        sb.append(WarpScriptLib.DEPTH);
        sb.append(SPACE + count + " - 1 ");
        sb.append(WarpScriptLib.SWAP);
        sb.append(SPACE + WarpScriptStack.MACRO_START + SPACE + WarpScriptLib.DROP + SPACE + WarpScriptLib.NULL);
        sb.append(WarpScriptStack.MACRO_END + SPACE + WarpScriptLib.FOR + SPACE + WarpScriptStack.MACRO_END + SPACE);
        // Remove the the deepest DEPTH - count elements
        sb.append(WarpScriptStack.MACRO_START + SPACE);
        sb.append(count + SPACE + WarpScriptLib.TOLIST);
        String symbol = genSymbol();
        sb.append(SPACE + "'" + symbol + "'" + SPACE + WarpScriptLib.STORE + SPACE + WarpScriptLib.CLEAR);
        sb.append(SPACE + "'" + symbol + "'" + SPACE + WarpScriptLib.LOAD + SPACE);
        sb.append(SPACE + "'" + symbol + "'" + SPACE + WarpScriptLib.FORGET + SPACE);
        sb.append(WarpScriptStack.MACRO_END + SPACE + WarpScriptLib.IFTE);
      }
      sb.append(LF());            
    }
  }
  
  private String genSymbol() {
    return String.valueOf(prefix + " " + varidx++);
  }
  
  private void indent(int count) {
    curindent += count;
  }
  
  public void indent() {
    indent(indent);
  }
  
  public void deindent() {
    indent(-indent);
  }
  
  private String indentation() {
    if (!needIndent) {
      return "";
    }
    needIndent = false;
    if (0 == curindent) {
      return "";
    } else {
      String indentation = "";
      int len = curindent;
      while(len > 0) {
        if (len > INDENTATION.length()) {
          indentation = indentation + INDENTATION;
          len -= INDENTATION.length();
        } else {
          indentation = indentation + INDENTATION.substring(0, len);
          len = 0;
        }
      }
      return indentation;
    }
  }
  
  /**
   * Compute the needed line feed string.
   * This methods looks into the current StringBuilder, as such it should only be called at the beginning of some
   * content being added.
   * @return Either LF or the empty String
   */
  private String LF() {
    needIndent = true;
    // Remove trailing spaces
    int len = sb.length();
    while(len > 0) {
      if (' ' == sb.charAt(len - 1)) {
        len--;
      } else {
        break;
      }
    }
    if (len != sb.length()) {
      sb.setLength(len);
    }
    // Don't insert LF if the last char is already a LF
    if (len >= 1 && '\n' == sb.charAt(len - 1)) {
      return "";
    }
    return LF;
  }
  
  private void section(ParserRuleContext context) {
    if (this.comments) {
      sb.append(indentation() + "'L" + context.start.getLine() + ":" + context.start.getCharPositionInLine() + "-" + context.stop.getLine() + ":" + context.stop.getCharPositionInLine() + "'" + SPACE + WarpScriptLib.SECTION);
      sb.append(LF());
    }
  }
  
  private void push(Object o) throws WarpScriptException {
    stack.incOps();
    if (macros.isEmpty()) {
      stack.push(o);
    } else {
      macros.get(macros.size() - 1).add(o);
    }
  }
  
  private void apply(WarpScriptStackFunction f) throws WarpScriptException {
    stack.incOps();
    if (macros.isEmpty()) {
      f.apply(stack);
    } else {
      macros.get(macros.size() - 1).add(f);
    }
  }
  
  private void eval(String code) throws WarpScriptException {
    if (eval) {
      if (macros.isEmpty()) {
        stack.exec(code);
      } else {
        macros.get(macros.size() - 1).add(code);
        macros.get(macros.size() - 1).add(EVAL);
      }
    } else {
      sb.append(indentation() + code);
      sb.append(LF());
    }
  }
}
