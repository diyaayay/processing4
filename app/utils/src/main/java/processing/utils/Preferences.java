/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Part of the Processing project - http://processing.org

  Copyright (c) 2014-19 The Processing Foundation

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License version 2
  as published by the Free Software Foundation.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package processing.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Storage class for user preferences and environment settings.
 * <P>
 * This class does not use the Properties class because .properties files use
 * ISO 8859-1 encoding, which is highly likely to be a problem when trying to
 * save sketch folders and locations. Like the rest of Processing, we use UTF8.
 * <p>
 * We don't use the Java Preferences API because it would entail writing to
 * the registry (on Windows), or an obscure file location (on Mac OS X) and
 * make it far more difficult (impossible) to remove the preferences.txt to
 * reset them (when they become corrupt), or to find the the file to make
 * edits for numerous obscure preferences that are not part of the preferences
 * window. If we added a generic editor (e.g. about:config in Mozilla) for
 * such things, we could start using the Java Preferences API. But wow, that
 * sounds like a lot of work. Not unlike writing this paragraph.
 */
public class Preferences {
  static final String DEFAULTS_FILE = "defaults.txt"; //$NON-NLS-1$
  static final String PREFS_FILE = "preferences.txt"; //$NON-NLS-1$

  /** Processing platform suffixes for platform-specific keys, mirrors PConstants.platformNames. */
  static final String[] PLATFORM_NAMES = { "other", "windows", "macos", "linux" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

  static Map<String, String> defaults;
  static Map<String, String> table = new HashMap<>();
  static File preferencesFile;
  private static boolean initialized = false;


  /**
   * Load the bundled defaults followed by the user's preferences.txt,
   * creating the user file from the defaults on first run.
   */
  static public void init() throws IOException {
    initialized = true;

    // start by loading the defaults, in case something
    // important was deleted from the user prefs
    try (InputStream defaultsStream =
             Preferences.class.getClassLoader().getResourceAsStream(DEFAULTS_FILE)) {
      if (defaultsStream == null) {
        throw new IOException("Could not find " + DEFAULTS_FILE + " on the classpath");
      }
      load(defaultsStream);
    }

    // Clone the defaults, then override them with the user's preferences.
    // This ensures that any new/added preference will be present.
    defaults = new HashMap<>(table);

    preferencesFile = getPreferencesFile();
    boolean firstRun = !preferencesFile.exists();
    if (!firstRun) {
      try (InputStream input = new FileInputStream(preferencesFile)) {
        load(input);
      }
    }

    if (checkSketchbookPref() || firstRun) {
      // write the file out if it didn't exist, or we migrated a 3.x path
      save();
    }
  }


  /**
   * Resolve the user preferences file, honoring an explicit override, and
   * otherwise placing it in the standard per-platform settings folder.
   */
  static File getPreferencesFile() throws IOException {
    String override = System.getProperty("processing.app.preferences.file");
    if (override != null && !override.isEmpty()) {
      return new File(override);
    }
    try {
      return new File(Settings.getFolder(), PREFS_FILE);
    } catch (Settings.SettingsFolderException e) {
      throw new IOException("Could not locate the settings folder", e);
    }
  }


  /**
   * For testing, pretend to load preferences without a real file.
   */
  static public void skipInit() {
    initialized = true;
  }


  /**
   * @return true if {@link #init()} has run; if not, we are probably not
   *   running the full application.
   */
  static public boolean isInitialized() {
    return initialized;
  }


  static public String getPreferencesPath() {
    return preferencesFile.getAbsolutePath();
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .


  /**
   * Load a set of key/value pairs from a UTF-8 encoded stream into 'table'.
   * Platform-specific extensions are stripped from keys so that a user's
   * preferences.txt has no platform-specific entries that could be lost on
   * reload.
   */
  static public void load(InputStream input) throws IOException {
    HashMap<String, String> platformSpecific = new HashMap<>();

    try (BufferedReader reader =
             new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty() || line.charAt(0) == '#') continue;

        line = line.replace("\\", "/");  // normalize slashes in paths

        // this won't properly handle = signs being in the text
        int equals = line.indexOf('=');
        if (equals != -1) {
          String key = line.substring(0, equals).trim();
          String value = line.substring(equals + 1).trim();
          if (!isPlatformSpecific(key, value, platformSpecific)) {
            table.put(key, value);
          }
        }
      }
    }
    // Now override the keys with any platform-specific defaults we've found.
    table.putAll(platformSpecific);
  }


  /**
   * @param key original key (may include platform extension)
   * @param value the value that goes with the key
   * @param specific where to put the key/value pairs for *this* platform
   * @return true if a platform-specific key
   */
  static protected boolean isPlatformSpecific(String key, String value,
                                              Map<String, String> specific) {
    for (String platform : PLATFORM_NAMES) {
      String ext = "." + platform;
      if (key.endsWith(ext)) {
        if (platform.equals(Platform.getName())) {
          key = key.substring(0, key.lastIndexOf(ext));
          // store this for later overrides
          specific.put(key, value);
        }
        // ignore platform-specific defaults for other platforms,
        // but return 'true' because it needn't be added to the big list
        return true;
      }
    }
    return false;
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .


  static public void save() throws IOException {
    // On startup, this is null, but ignore it. It's trying to update the
    // prefs for the open sketch before init() has been called.
    if (preferencesFile == null) {
      return;
    }

    File dir = preferencesFile.getParentFile();
    File preferencesTemp = File.createTempFile("preferences", ".txt", dir);
    if (!preferencesTemp.setWritable(true, false)) {
      throw new IOException("Could not set " + preferencesTemp + " writable");
    }

    // Fix for 0163 to properly use Unicode when writing preferences.txt
    try (PrintWriter writer = new PrintWriter(
             new OutputStreamWriter(new FileOutputStream(preferencesTemp), StandardCharsets.UTF_8))) {
      String[] keyList = table.keySet().toArray(new String[0]);
      // Sorting is really helpful for debugging, diffing, and finding keys
      Arrays.sort(keyList);
      for (String key : keyList) {
        writer.println(key + "=" + table.get(key)); //$NON-NLS-1$
      }
    }

    // Rename preferences.txt to preferences.old
    File oldPreferences = new File(dir, "preferences.old");
    if (oldPreferences.exists() && !oldPreferences.delete()) {
      throw new IOException("Could not delete preferences.old");
    }
    if (preferencesFile.exists() && !preferencesFile.renameTo(oldPreferences)) {
      throw new IOException("Could not replace preferences.old");
    }
    // Make the temporary file into the real preferences
    if (!preferencesTemp.renameTo(preferencesFile)) {
      throw new IOException("Could not move preferences file into place");
    }
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .


  // all the information from preferences.txt

  static public String get(String attribute) {
    return table.get(attribute);
  }


  static public String getDefault(String attribute) {
    return defaults.get(attribute);
  }


  static public void set(String attribute, String value) {
    table.put(attribute, value);
  }


  static public void unset(String attribute) {
    table.remove(attribute);
  }


  static public boolean getBoolean(String attribute) {
    return Boolean.parseBoolean(get(attribute));
  }


  static public void setBoolean(String attribute, boolean value) {
    set(attribute, value ? "true" : "false"); //$NON-NLS-1$ //$NON-NLS-2$
  }


  static public int getInteger(String attribute) {
    try {
      return Integer.parseInt(get(attribute));
    } catch (NumberFormatException err) {
      try {
        return Integer.parseInt(getDefault(attribute));
      } catch (NumberFormatException err2) {
        throw new IllegalArgumentException("Cannot parse: " + attribute);
      }
    }
  }


  static public void setInteger(String key, int value) {
    set(key, String.valueOf(value));
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .


  /**
   * Check for a 4.0 sketchbook location, and if none exists,
   * try to grab it from the 3.0 sketchbook location.
   * @return true if a location was found and the pref didn't exist
   */
  static protected boolean checkSketchbookPref() {
    // If a 4.0 sketchbook location has never been inited
    if (getSketchbookPath() == null) {
      String threePath = get("sketchbook.path.three"); //$NON-NLS-1$
      // If they've run the 3.0 version, start with that location
      if (threePath != null) {
        setSketchbookPath(threePath);
        return true;  // save the sketchbook right away
      }
      // Otherwise it'll be null, and reset properly by the application
    }
    return false;
  }


  static public String getOldSketchbookPath() {
    return get("sketchbook.path.three"); //$NON-NLS-1$
  }


  static public String getSketchbookPath() {
    return get("sketchbook.path.four"); //$NON-NLS-1$
  }


  static protected void setSketchbookPath(String path) {
    set("sketchbook.path.four", path); //$NON-NLS-1$
  }
}
