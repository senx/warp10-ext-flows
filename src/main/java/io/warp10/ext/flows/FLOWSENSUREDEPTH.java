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

package io.warp10.ext.flows;

import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

public class FLOWSENSUREDEPTH extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  
  public FLOWSENSUREDEPTH(String name) {
    super(name);
  }
  
  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    long count = ((Long) stack.pop()).longValue();
    
    long delta = stack.depth() - count;
    
    if (delta < 0) {
      while(delta < 0) {
        stack.push(null);
        delta++;
      }
    } else if (delta > 0) {
      Object[] args = stack.popn((int) count);
      stack.clear();
      for (Object arg: args) {
        stack.push(arg);
      }
    }

    return stack;
  }

}
