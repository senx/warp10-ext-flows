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
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.warp10.WarpConfig;
import io.warp10.script.MemoryWarpScriptStack;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStack.Macro;
import io.warp10.script.WarpScriptStackRegistry;
import io.warp10.sensision.Sensision;
import io.warp10.warp.sdk.MacroResolver;
import sun.net.www.protocol.file.FileURLConnection;

/**
 * Macro library built by adding macros from various files, loaded from a root directory
 * or from the classpath
 *
 * TODO(hbs): add support for secure script (the keystore is not initialized)
 */
public class FLoWSMacroLibraryResolver extends MacroResolver {

  private static final Logger LOG = LoggerFactory.getLogger(FLoWSMacroLibraryResolver.class);

  /**
   * Number of macros loaded from jars and the classpath which are currently cached
   */
  private static final String SENSISION_CLASS_FLOWS_LIBRARY_CACHED = "flows.library.macros";

  /**
   * Size of macro cache for the macros loaded from the classpath
   */
  public static final String FLOWS_LIBRARY_CACHE_SIZE = "flows.library.cache.size";

  /**
   * Default TTL for macros loaded from the classpath
   */
  public static final String FLOWS_LIBRARY_TTL = "flows.library.ttl";

  /**
   * Maximum TTL for a macro loaded from the classpath
   */
  public static final String FLOWS_LIBRARY_TTL_HARD = "flows.library.ttl.hard";


  private static final Map<String,Macro> macros;

  private static final int DEFAULT_CACHE_SIZE = 10000;

  /**
   * Default TTL for macros loaded on demand
   */
  private static final long DEFAULT_MACRO_TTL = 600000L;

  /**
   * Default TTL for loaded macros
   */
  private static long ttl = DEFAULT_MACRO_TTL;

  /**
   * Maximum TTL for loaded macros
   */
  private static long hardTtl = Long.MAX_VALUE >> 2;

  private static final int maxcachesize;

  static {
    //
    // Create macro map
    //

    maxcachesize = Integer.parseInt(WarpConfig.getProperty(FLOWS_LIBRARY_CACHE_SIZE, Integer.toString(DEFAULT_CACHE_SIZE)));

    macros = new LinkedHashMap<String,Macro>() {
      @Override
      protected boolean removeEldestEntry(java.util.Map.Entry<String,Macro> eldest) {
        int size = this.size();
        Sensision.set(SENSISION_CLASS_FLOWS_LIBRARY_CACHED, Sensision.EMPTY_LABELS, size);
        return size > maxcachesize;
      }
    };

    ttl = Long.parseLong(WarpConfig.getProperty(FLOWS_LIBRARY_TTL, Long.toString(DEFAULT_MACRO_TTL)));
    hardTtl = Long.parseLong(WarpConfig.getProperty(FLOWS_LIBRARY_TTL_HARD, Long.toString(Long.MAX_VALUE >>> 2)));
  }

  public static void addJar(String path) throws WarpScriptException {
    addJar(path, null);
  }

  private static void addJar(String path, String resource) throws WarpScriptException {
    //
    // Extract basename of path
    //

    File f = new File(path);

    if (!f.exists() || !f.isFile()) {
      throw new WarpScriptException("File not found " + f.getAbsolutePath());
    }

    JarFile jar = null;

    try {
      String basename = f.getName();

      jar = new JarFile(f);

      Enumeration<JarEntry> entries = jar.entries();

      while(entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();

        if (entry.isDirectory()) {
          continue;
        }

        String name = entry.getName();

        if (!name.endsWith(FLoWSWarpScriptExtension.FLOWS_FILE_EXTENSION)) {
          continue;
        }

        if (null != resource && !resource.equals(name)) {
          continue;
        }

        name = name.substring(0, name.length() - FLoWSWarpScriptExtension.FLOWS_FILE_EXTENSION.length());

        InputStream in = jar.getInputStream(entry);

        Macro macro = loadMacro(jar, in, name);

        //
        // Store resulting macro under 'name'
        //

        // Make macro a secure one
        macro.setSecure(true);

        macros.put(name, macro);
      }

      if (maxcachesize == macros.size()) {
        LOG.warn("Some cached library macros were evicted.");
      }

      Sensision.set(SENSISION_CLASS_FLOWS_LIBRARY_CACHED, Sensision.EMPTY_LABELS, macros.size());

    } catch (IOException ioe) {
      throw new WarpScriptException("Encountered error while loading " + f.getAbsolutePath(), ioe);
    } finally {
      if (null != jar) { try { jar.close(); } catch (IOException ioe) {} }
    }
  }

  public static Macro loadMacro(Object root, InputStream in, String name) throws WarpScriptException {

    MemoryWarpScriptStack stack = null;

    try {
      byte[] buf = new byte[8192];
      StringBuilder sb = new StringBuilder();

      ByteArrayOutputStream out = new ByteArrayOutputStream();

      while(true) {
        int len = in.read(buf);

        if (len < 0) {
          break;
        }

        out.write(buf, 0, len);
      }

      in.close();

      byte[] data = out.toByteArray();

      sb.setLength(0);
      sb.append(" ");

      sb.append(new String(data, StandardCharsets.UTF_8));

      sb.append("\n");

      stack = new MemoryWarpScriptStack(null, null, new Properties());
      stack.setAttribute(WarpScriptStack.ATTRIBUTE_NAME, "[WarpScriptMacroLibrary " + name + "]");

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
        throw new WarpScriptException("Stack depth was not 1 after the code execution.");
      }

      if (!(stack.peek() instanceof Macro)) {
        throw new WarpScriptException("No macro was found on top of the stack.");
      }

      Macro macro = (Macro) stack.pop();
      macro.setSecure(true);
      macro.setNameRecursive(name);

      long macroTtl = ttl;

      if (null != stack.getAttribute(WarpScriptStack.ATTRIBUTE_MACRO_TTL)) {
        macroTtl = (long) stack.getAttribute(WarpScriptStack.ATTRIBUTE_MACRO_TTL);
      }

      if (macroTtl > hardTtl) {
        macroTtl = hardTtl;
      }

      // Set expiry. Note using a ttl too long will wrap around the sum and will
      // make the macro expire too early

      try {
        macro.setExpiry(Math.addExact(System.currentTimeMillis(), ttl));
      } catch (ArithmeticException ae) {
        macro.setExpiry(Long.MAX_VALUE - 1);
      }

      return macro;
    } catch (IOException ioe) {
      throw new WarpScriptException(ioe);
    } finally {
      WarpScriptStackRegistry.unregister(stack);
      try { in.close(); } catch (IOException ioe) {}
    }
  }


  @Override
  public Macro findMacro(WarpScriptStack stack, String name) throws WarpScriptException {
    // Reject names with relative path components in them or starting with '/'
    if (name.contains("/../") || name.contains("/./") || name.startsWith("../") || name.startsWith("./") || name.startsWith("/")) {
      return null;
    }

    Macro macro = (Macro) macros.get(name);

    //
    // The macro is not (yet) known, we will attempt to load it from the
    // classpath
    //

    if (null == macro || macro.isExpired()) {
      String rsc = name + FLoWSWarpScriptExtension.FLOWS_FILE_EXTENSION;
      URL url = FLoWSMacroLibraryResolver.class.getClassLoader().getResource(rsc);

      if (null != url) {
        try {
          URLConnection conn = url.openConnection();

          if (conn instanceof JarURLConnection) {
            //
            // This case is when the requested macro is in a jar
            //
            final JarURLConnection connection = (JarURLConnection) url.openConnection();
            final URL fileurl = connection.getJarFileURL();
            File f = new File(fileurl.toURI());
            addJar(f.getAbsolutePath(), rsc);
            macro = (Macro) macros.get(name);
          } else if (conn instanceof FileURLConnection) {
            //
            // This case is when the requested macro is in the classpath but not in a jar.
            //
            String urlstr = url.toString();
            File root = new File(urlstr.substring(0, urlstr.length() - name.length()  - FLoWSWarpScriptExtension.FLOWS_FILE_EXTENSION.length()));
            macro = loadMacro(root, conn.getInputStream(), name);
            macros.put(name, macro);
          }
        } catch (URISyntaxException use) {
          throw new WarpScriptException("Error while loading '" + name + "'", use);
        } catch (IOException ioe) {
          throw new WarpScriptException("Error while loading '" + name + "'", ioe);
        }
      }
    }

    return macro;
  }
}
