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
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.warp10.ThrowableUtils;
import io.warp10.WarpConfig;
import io.warp10.crypto.SipHashInline;
import io.warp10.script.MemoryWarpScriptStack;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStack.Macro;
import io.warp10.script.WarpScriptStackRegistry;
import io.warp10.script.functions.MSGFAIL;
import io.warp10.sensision.Sensision;
import io.warp10.warp.sdk.MacroResolver;

public class FLoWSMacroRepositoryResolver extends MacroResolver {

  private static final MSGFAIL MSGFAIL_FUNC = new MSGFAIL("MSGFAIL");

  /**
   * Number of macros known in the repository
   */
  public static final String SENSISION_CLASS_FLOWS_REPOSITORY_MACROS = "flows.repository.macros";

  /**
   * Macro Repository root directory
   */
  public static final String REPOSITORY_DIRECTORY = "flows.repository.directory";

  /**
   * Number of macros loaded from 'warpscript.repository.directory' to keep in memory
   */
  public static final String REPOSITORY_CACHE_SIZE = "flows.repository.cache.size";

  /**
   * Default TTL for macros loaded on demand
   */
  public static final String REPOSITORY_TTL = "flows.repository.ttl";

  /**
   * TTL to use for failed macros
   */
  public static final String REPOSITORY_TTL_FAILED = "flows.repository.ttl.failed";

  /**
   * Default TTL for macros loaded on demand
   */
  private static final long DEFAULT_MACRO_TTL = 600000L;

  /**
   * Default TTL for macros which failed loading
   */
  private static final long DEFAULT_FAILED_MACRO_TTL = 10000L;

  private static long[] SIP_KEYS = { 32232312312312L, 543534535435L };

  /**
   * Directory where FLoWS files are
   */
  private static String directory;

  /**
   * Default TTL for loaded macros
   */
  private static long ttl = DEFAULT_MACRO_TTL;

  /**
   * Default TTL for failed macros
   */
  private static long failedTtl = DEFAULT_FAILED_MACRO_TTL;

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
   * Actual macros
   */
  private final static Map<String,Macro> macros;

  private static final int DEFAULT_CACHE_SIZE = 10000;

  private static final int maxcachesize;

  static {
    //
    // Create macro map
    //

    maxcachesize = Integer.parseInt(WarpConfig.getProperty(REPOSITORY_CACHE_SIZE, Integer.toString(DEFAULT_CACHE_SIZE)));

    macros = new LinkedHashMap<String,Macro>() {
      @Override
      protected boolean removeEldestEntry(java.util.Map.Entry<String,Macro> eldest) {
        int size = this.size();
        Sensision.set(SENSISION_CLASS_FLOWS_REPOSITORY_MACROS, Sensision.EMPTY_LABELS, size);
        return size > maxcachesize;
      }
    };
  }

  public FLoWSMacroRepositoryResolver() {
    //
    // Extract root directory
    //

    String dir = WarpConfig.getProperty(REPOSITORY_DIRECTORY);

    if (null == dir) {
      return;
    }

    directory = dir;

    ttl = Long.parseLong(WarpConfig.getProperty(REPOSITORY_TTL, Long.toString(DEFAULT_MACRO_TTL)));
    failedTtl = Long.parseLong(WarpConfig.getProperty(REPOSITORY_TTL_FAILED, Long.toString(DEFAULT_FAILED_MACRO_TTL)));
  }

  @Override
  public Macro findMacro(WarpScriptStack stack, String name) throws WarpScriptException {
    Macro macro = null;
    synchronized(macros) {
      macro = (Macro) macros.get(name);
    }

    // Check if macro has expired
    if (null != macro && macro.isExpired()) {
      macro = null;
    }

    if (null == macro) {
      macro = loadMacro(name, null);
      if (null != macro) {
        // Store the recently loaded macro in the map
        synchronized(macros) {
          macros.put(name, macro);
        }
      }
    }

    return macro;
  }

  /**
   * Load a macro stored in a file
   *
   * @param name of macro
   * @param file containing the macro
   * @return
   */
  private static Macro loadMacro(String name, File file) {

    if (null == name && null == file) {
      return null;
    }

    //
    // Read content
    //

    String rootdir = new File(directory).getAbsolutePath();

    if (null == file) {
      // Replace '/' with the platform separator
      if (!"/".equals(File.separator)) {
        file = new File(rootdir, name.replaceAll("/", File.separator) + FLoWSWarpScriptExtension.FLOWS_FILE_EXTENSION);
      } else {
        file = new File(rootdir, name + FLoWSWarpScriptExtension.FLOWS_FILE_EXTENSION);
      }
      // Macros should reside in the configured root directory
      if (!file.getAbsolutePath().startsWith(rootdir)) {
        return null;
      }
    }

    if (null == name) {
      name = file.getAbsolutePath().substring(rootdir.length() + 1).replaceAll(Pattern.quote(FLoWSWarpScriptExtension.FLOWS_FILE_EXTENSION) + "$", "");
      name = name.replaceAll(Pattern.quote(File.separator), "/");
    }

    // Reject names with relative path components in them or starting with '/'
    if (name.contains("/../") || name.contains("/./") || name.startsWith("../") || name.startsWith("./") || name.startsWith("/")) {
      return null;
    }

    // Name should contain "/" as macros should reside under a subdirectory
    if (!name.contains("/")) {
      return null;
    }

    byte[] buf = new byte[8192];

    StringBuilder sb = new StringBuilder();

    sb.append(" ");

    MemoryWarpScriptStack stack = null;

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

      if (!file.exists()) {
        return null;
      }

      FileInputStream in = new FileInputStream(file);
      ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());

      while(true) {
        int len = in.read(buf);

        if (len < 0) {
          break;
        }

        out.write(buf, 0, len);
      }

      in.close();

      byte[] data = out.toByteArray();

      // Compute hash to check if the file changed or not

      long hash = SipHashInline.hash24_palindromic(SIP_KEYS[0], SIP_KEYS[1], data);

      Macro old = macros.get(name);

      // Re-use the same macro if its fingerprint did not change and it has not expired
      if (null != old && hash == old.getFingerprint() && !old.isExpired()) {
        return old;
      }

      sb.append(new String(data, StandardCharsets.UTF_8));

      sb.append("\n");

      stack = new MemoryWarpScriptStack(null, null);
      stack.setAttribute(WarpScriptStack.ATTRIBUTE_NAME, "[FLoWSMacroRepository " + name + "]");

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

      Macro macro = (Macro) stack.pop();

      // Set expiration if ondemand is set and a ttl was specified

      try {
        if (null != stack.getAttribute(WarpScriptStack.ATTRIBUTE_MACRO_TTL)) {
          macro.setExpiry(Math.addExact(System.currentTimeMillis(), (long) stack.getAttribute(WarpScriptStack.ATTRIBUTE_MACRO_TTL)));
        } else {
          macro.setExpiry(Math.addExact(System.currentTimeMillis(), ttl));
        }
      } catch (ArithmeticException ae) {
        macro.setExpiry(Long.MAX_VALUE - 1);
      }

      macro.setFingerprint(hash);

      // Make macro a secure one
      macro.setSecure(true);

      macro.setNameRecursive(name);

      return macro;
    } catch(Exception e) {
      // Replace macro with a FAIL indicating the error message
      Macro macro = new Macro();
      macro.add("[" + System.currentTimeMillis() + "] Error while loading macro '" + name + "': " + ThrowableUtils.getErrorMessage(e, 1024));
      macro.add(MSGFAIL_FUNC);
      // Set the expiry to half the refresh interval so we get a chance to load a newly provided file
      macro.setExpiry(System.currentTimeMillis() + failedTtl);

      macro.setFingerprint(0L);
      return macro;
    } finally {
      WarpScriptStackRegistry.unregister(stack);
      loading.get().remove(loading.get().size() - 1);
    }
  }
}
