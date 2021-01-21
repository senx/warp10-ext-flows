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

import java.util.HashMap;
import java.util.Map;

import io.warp10.warp.sdk.MacroResolver;
import io.warp10.warp.sdk.WarpScriptExtension;

public class FLoWSWarpScriptExtension extends WarpScriptExtension {

  private static final Map<String,Object> functions;

  public static final String FLOWS_ASSERTDEPTH = "FLOWS.ASSERTDEPTH";
  public static final String FLOWS_ENSUREDEPTH = "FLOWS.ENSUREDEPTH";

  public static final String FLOWS = "FLOWS";

  public static final FLOWS FLOWSINSTANCE = new FLOWS(FLOWS, true);

  static {
    functions = new HashMap<String, Object>();

    functions.put("FLOWS->", new FLOWS("FLOWS->", false));
    functions.put(FLOWS, FLOWSINSTANCE);
    functions.put(FLOWS_ASSERTDEPTH, new FLOWSASSERTDEPTH(FLOWS_ASSERTDEPTH));
    functions.put(FLOWS_ENSUREDEPTH, new FLOWSENSUREDEPTH(FLOWS_ENSUREDEPTH));

    MacroResolver.register(new FLoWSMacroRepositoryResolver());
    MacroResolver.register(new FLoWSMacroLibraryResolver());
    MacroResolver.register(new FLoWSWarpFleetResolver());
  }

  public static final String FLOWS_FILE_EXTENSION = ".flows";

  @Override
  public Map<String, Object> getFunctions() {
    return functions;
  }
}
