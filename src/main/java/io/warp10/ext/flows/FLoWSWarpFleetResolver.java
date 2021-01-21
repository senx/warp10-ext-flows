//
// Copyright 2021  SenX S.A.S.
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.warp10.Revision;
import io.warp10.ThrowableUtils;
import io.warp10.WarpConfig;
import io.warp10.continuum.Configuration;
import io.warp10.continuum.store.Constants;
import io.warp10.script.MemoryWarpScriptStack;
import io.warp10.script.WarpFleetMacroRepository;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStack.Macro;
import io.warp10.script.WarpScriptStackRegistry;
import io.warp10.script.binary.ADD;
import io.warp10.script.binary.SUB;
import io.warp10.script.functions.HUMANDURATION;
import io.warp10.script.functions.MSGFAIL;
import io.warp10.script.functions.NOW;
import io.warp10.sensision.Sensision;
import io.warp10.warp.sdk.MacroResolver;

public class FLoWSWarpFleetResolver  extends MacroResolver {

  /**
   * Comma separated list of configured WarpFleet™ repositories
   */
  public static final String WARPFLEET_MACROS_REPOS = "warpfleet.macros.repos";

  private static final String SENSISION_CLASS_WARPFLEET_FLOWS_MACROS_CACHED = "warpfleet.flows.macros.cached";

  private static final MSGFAIL MSGFAIL_FUNC = new MSGFAIL("MSGFAIL");
  private static final NOW NOW_FUNC = new NOW("NOW");
  private static final SUB SUB_FUNC = new SUB("-");
  private static final ADD ADD_FUNC = new ADD("+");
  private static final HUMANDURATION HUMANDURATION_FUNC = new HUMANDURATION("HUMANDURATION");

  private static final int FINGERPRINT_UNKNOWN = -1;

  /**
   * List of macro names to avoid loops in macro loading
   */
  private static ThreadLocal<List<String>> loading = new ThreadLocal<List<String>>() {
    @Override
    protected List<String> initialValue() {
      return new ArrayList<String>();
    }
  };

  /**
   * Default macro TTL in ms
   */
  private static final long DEFAULT_TTL = 600000L;

  /**
   * Lower limit for macro TTL in ms. Use this to limit how often a macro will be fetched from a repo.
   */
  private static final long DEFAULT_TTL_MIN = 60000L;

  /**
   * Upper limit for macro TTL in ms. Use this to limit to ensure macros get refreshed from a repo.
   */
  private static final long DEFAULT_TTL_MAX = 24 * 3600 * 1000L;

  /**
   * Default TTL for macros which failed to load
   */
  private static final long DEFAULT_TTL_FAILED = 10000L;

  /**
   * Default TTL for macros which were not found
   */
  private static final long DEFAULT_TTL_UNKNOWN = 0L;

  private static final int DEFAULT_READ_TIMEOUT = 10000;
  private static final int DEFAULT_CONNECT_TIMEOUT = 5000;

  private static long ttl = DEFAULT_TTL;
  private static long minttl = DEFAULT_TTL_MIN;
  private static long maxttl = DEFAULT_TTL_MAX;
  private static long failedTtl = DEFAULT_TTL_FAILED;
  private static long unknownTtl = DEFAULT_TTL_UNKNOWN;
  private static int readTimeout = DEFAULT_READ_TIMEOUT;
  private static int connectTimeout = DEFAULT_CONNECT_TIMEOUT;

  private static final int DEFAULT_CACHE_SIZE = 10000;

  private static Map<String,Macro> macros = null;

  public FLoWSWarpFleetResolver() {
    //
    // Create macro map
    //

    final int maxcachesize = Integer.parseInt(WarpConfig.getProperty(Configuration.WARPFLEET_CACHE_SIZE, Integer.toString(DEFAULT_CACHE_SIZE)));

    FLoWSWarpFleetResolver.macros = new LinkedHashMap<String,Macro>() {
      @Override
      protected boolean removeEldestEntry(java.util.Map.Entry<String,Macro> eldest) {
        int size = this.size();
        Sensision.set(SENSISION_CLASS_WARPFLEET_FLOWS_MACROS_CACHED, Sensision.EMPTY_LABELS, size);
        return size > maxcachesize;
      }
    };

    //
    // Extract TTLs
    //

    minttl = Long.parseLong(WarpConfig.getProperty(Configuration.WARPFLEET_MACROS_TTL_MIN, Long.toString(DEFAULT_TTL_MIN)));
    maxttl = Long.parseLong(WarpConfig.getProperty(Configuration.WARPFLEET_MACROS_TTL_MAX, Long.toString(DEFAULT_TTL_MAX)));
    ttl = Long.parseLong(WarpConfig.getProperty(Configuration.WARPFLEET_MACROS_TTL, Long.toString(DEFAULT_TTL)));
    failedTtl = Long.parseLong(WarpConfig.getProperty(Configuration.WARPFLEET_MACROS_TTL_FAILED, Long.toString(DEFAULT_TTL_FAILED)));
    unknownTtl = Long.parseLong(WarpConfig.getProperty(Configuration.WARPFLEET_MACROS_TTL_UNKNOWN, Long.toString(DEFAULT_TTL_UNKNOWN)));

    readTimeout = Integer.parseInt(WarpConfig.getProperty(Configuration.WARPFLEET_TIMEOUT_READ, Integer.toString(DEFAULT_READ_TIMEOUT)));
    connectTimeout = Integer.parseInt(WarpConfig.getProperty(Configuration.WARPFLEET_TIMEOUT_CONNECT, Integer.toString(DEFAULT_CONNECT_TIMEOUT)));
  }

  @Override
  public Macro findMacro(WarpScriptStack callingStack, String name) throws WarpScriptException {
    // Do not attempt to fetch macros remotely if the stack was instructed not to
    if (Boolean.TRUE.equals(callingStack.getAttribute(WarpFleetMacroRepository.ATTRIBUTE_WARPFLEET_DISABLE))) {
      return null;
    }

    // Reject names with relative path components in them or starting with '/'
    if (name.contains("/../") || name.contains("/./") || name.startsWith("../") || name.startsWith("./") || name.startsWith("/")) {
      return null;
    }

    Macro macro = null;

    //
    // Attempt to fetch the macro from each repository defined in the stack
    // or from the default ones if no directory is defined in the stack
    //

    List<String> repos = WarpFleetMacroRepository.getRepos(callingStack);

    byte[] buf = new byte[2048];

    String macroURL = null;

    try {

      if (loading.get().contains(name)) {
        // Build the macro loading sequence
        StringBuilder seq = new StringBuilder();
        for(String macname: loading.get()) {
          if (seq.length() > 0) {
            seq.append(" >>> ");
          }
          seq.append("@");
          seq.append(macname);
        }
        throw new WarpScriptException("Invalid recursive macro loading (" + seq.toString() + ")");
      }

      loading.get().add(name);

      for (String repo: repos) {

        //
        // Check the macro cache
        //

        macroURL = repo + (repo.endsWith("/") ? "" : "/") + name;

        synchronized(macros) {
          macro = macros.get(macroURL);

          //
          // If the macro has expired, remove it from the cache
          //
          if (null != macro && macro.isExpired()) {
            macros.remove(macroURL);
            macro = null;
          }
        }

        //
        // If the macro is defined and its fingerprint is not the special value
        // used for macros which were not found in the given repo, return it.
        //

        if (null != macro && FINGERPRINT_UNKNOWN != macro.getFingerprint()) {
          return macro;
        }

        // If the macro is defined but its fingerprint is UNKNOWN, skip this repo
        if (null != macro && FINGERPRINT_UNKNOWN == macro.getFingerprint()) {
          continue;
        }

        InputStream in = null;

        MemoryWarpScriptStack stack = null;

        try {
          URL url = new URL(macroURL + FLoWSWarpScriptExtension.FLOWS_FILE_EXTENSION);

          URLConnection conn = url.openConnection();

          if (conn instanceof HttpURLConnection) {
            ((HttpURLConnection) conn).setRequestProperty("X-Warp10-Revision", Revision.REVISION);
            ((HttpURLConnection) conn).setReadTimeout(readTimeout);
            ((HttpURLConnection) conn).setConnectTimeout(connectTimeout);
          }

          in = conn.getInputStream();

          ByteArrayOutputStream out = new ByteArrayOutputStream();

          while(true) {
            int len = in.read(buf);

            if (len < 0) {
              break;
            }

            out.write(buf, 0, len);
          }

          byte[] data = out.toByteArray();

          StringBuilder sb = new StringBuilder();
          sb.append(" ");
          sb.append(new String(data, StandardCharsets.UTF_8));
          sb.append("\n");

          stack = new MemoryWarpScriptStack(null, null);
          stack.setAttribute(WarpScriptStack.ATTRIBUTE_NAME, "[FLoWSWarpFleetResolver " + url.toString() + "]");

          stack.maxLimits();
          stack.setAttribute(WarpScriptStack.ATTRIBUTE_MACRO_NAME, name);

          //
          // Execute the code
          //
          stack.push(sb.toString());
          FLoWSWarpScriptExtension.FLOWSINSTANCE.apply(stack);

          //
          // Ensure the resulting stack is one level deep and has a macro on top
          //

          if (1 != stack.depth()) {
            throw new WarpScriptException("Expected a single value after the code execution, found " + stack.depth());
          }

          if (!(stack.peek() instanceof Macro)) {
            throw new WarpScriptException("Execution did not return a macro.");
          }

          //
          // Store resulting macro under 'name'
          //

          macro = (Macro) stack.pop();

          if (null != callingStack.getAttribute(WarpScriptStack.ATTRIBUTE_MACRO_TTL)) {
            long macrottl = (long) callingStack.getAttribute(WarpScriptStack.ATTRIBUTE_MACRO_TTL);
            if (macrottl < minttl) {
              macrottl = minttl;
            }
            if (macrottl > maxttl) {
              macrottl = maxttl;
            }
            macro.setExpiry(System.currentTimeMillis() + macrottl);
          } else {
            macro.setExpiry(System.currentTimeMillis() + ttl);
          }

          macro.setNameRecursive(name);

          synchronized(macros) {
            macros.put(macroURL, macro);
          }

          return macro;
        } catch (MalformedURLException mue) {
          //
          // We set the macro to an empty one with a specific fingerprint so we
          // don't pound the repo when attempting again to access this macro
          //
          macro = new Macro();
          macro.setFingerprint(FINGERPRINT_UNKNOWN);
          macro.setExpiry(System.currentTimeMillis() + failedTtl);
          synchronized(macros) {
            macros.put(macroURL, macro);
          }
        } catch (IOException ioe) {
          //
          // We set the macro to an empty one with a specific fingerprint so we
          // don't pound the repo when attempting again to access this macro
          //
          macro = new Macro();
          macro.setFingerprint(FINGERPRINT_UNKNOWN);
          macro.setExpiry(System.currentTimeMillis() + failedTtl);
          synchronized(macros) {
            macros.put(macroURL, macro);
          }
        } finally {
          WarpScriptStackRegistry.unregister(stack);
          if (null != in) {
            try { in.close(); } catch (Exception e) {}
          }
        }
      }
    } catch (WarpScriptException wse) {
      // Replace macro with a FAIL indicating the error message
      macro = new Macro();
      macro.add("[" + System.currentTimeMillis() + "] Error while loading macro '" + name + "': " + ThrowableUtils.getErrorMessage(wse, 1024) + ", result cached for ");
      long expiry_ts = System.currentTimeMillis() + failedTtl;
      macro.add(expiry_ts * Constants.TIME_UNITS_PER_MS);
      macro.add(NOW_FUNC);
      macro.add(SUB_FUNC);
      macro.add(HUMANDURATION_FUNC);
      macro.add(ADD_FUNC);
      macro.add(MSGFAIL_FUNC);
      // Set the expiry
      macro.setExpiry(expiry_ts);
    } finally {
      loading.get().remove(loading.get().size() - 1);
    }

    //
    // If the macro was not found, replace it with a 'failed' macro so we do not pound
    // the WarpFleet repos for non existing macros
    //

    if (null == macro && unknownTtl > 0) {
      macro = new Macro();
      macro.add("[" + System.currentTimeMillis() + "] Macro '" + name + "' was not found in any of the WarpFleet repositories, result cached for ");
      long expiry_ts = System.currentTimeMillis() + unknownTtl;
      macro.add(expiry_ts * Constants.TIME_UNITS_PER_MS);
      macro.add(NOW_FUNC);
      macro.add(SUB_FUNC);
      macro.add(HUMANDURATION_FUNC);
      macro.add(ADD_FUNC);
      macro.add(MSGFAIL_FUNC);
      // Set the expiry
      macro.setExpiry(expiry_ts);
    }

    if (null != macro && null != macroURL) {
      synchronized(macros) {
        macros.put(macroURL, macro);
      }
    }

    if (null == macro || FINGERPRINT_UNKNOWN != macro.getFingerprint()) {
      return macro;
    } else {
      return null;
    }
  }
}

