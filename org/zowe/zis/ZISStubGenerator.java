/*
  This program and the accompanying materials are
  made available under the terms of the Eclipse Public License v2.0 which accompanies
  this distribution, and is available at https://www.eclipse.org/legal/epl-v20.html

  SPDX-License-Identifier: EPL-2.0

  Copyright Contributors to the Zowe Project.
 */

package org.zowe.zis;

import java.io.IOException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.PrintStream;

/**
 The purpose of the stubs is to be able to build ZIS plugins that do NOT
 statically link zowe-common-c and ZIS base code into the plugin.
 */
public class ZISStubGenerator {

    private final String hFileName;

    private enum DispatchMode {R12, ZVTE}

    public ZISStubGenerator(String hFileName) {
        this.hFileName = hFileName;
    }

    private static final String[] hlasmProlog =
            {
                    "         TITLE 'ZISSTUBS'",
                    "         ACONTROL AFPR",
                    "ZISSTUBS CSECT",
                    "ZISSTUBS AMODE 64",
                    "ZISSTUBS RMODE ANY",
                    "         SYSSTATE ARCHLVL=2,AMODE64=YES",
                    "         IEABRCX DEFINE",
                    ".* The HLASM GOFF option is needed to assemble this program"
            };

    private static final String[] hlasmEpilog =
            {
                    "         EJECT",
                    "ZISSTUBS CSECT ,",
                    "         END"
            };

    private void writeLines(PrintStream out, String[] lines) {
        for (String line : lines) {
            out.printf("%s\n", line);
        }
    }

    private BufferedReader openEbcdic(String filename) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(filename), "Cp1047"));
    }

    private void generateCode(PrintStream out, boolean generateASM, DispatchMode dispatchMode) throws IOException {
        BufferedReader reader = openEbcdic(hFileName);
        String line;
        if (generateASM) {
            writeLines(out, hlasmProlog);
        }
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("#define ZIS_STUB_")) {
                int spacePos = line.indexOf(' ', 17);
                if (spacePos == -1) {
                    throw new IOException(String.format("bad define '%s'\n", line));
                }
                String symbol = line.substring(17, spacePos);
                // System.out.printf("symbol: %s\n",symbol);
                String tail = line.substring(spacePos + 1).trim();
                int tailSpacePos = tail.indexOf(' ');
                if (tailSpacePos == -1) {
                    throw new IOException(String.format("bad define constant '%s'\n", line));
                }
                int index = Integer.parseInt(tail.substring(0, tailSpacePos));
                tail = tail.substring(tailSpacePos).trim();
                if (!tail.startsWith("/*") ||
                        !tail.endsWith("*/")) {
                    throw new IOException(String.format("comment with C functionName missing in '%s'\n", line));
                }

                String commentText = tail.substring(2, tail.length() - 2).trim();
                String functionName;
                boolean isMapped = false;
                if (commentText.endsWith(" mapped")) {
                    functionName = commentText.substring(0, commentText.length() - 7);
                    isMapped = true;
                } else {
                    functionName = commentText;
                }

                if (generateASM) {
                    out.printf("         ENTRY %s\n", symbol);
                    if (!isMapped) {
                        out.printf("%-8.8s ALIAS C'%s'\n", symbol, functionName);
                    }
                    switch (dispatchMode) {
                        case ZVTE -> {
                            out.printf("%-8.8s LLGT 15,16(0,0)       CVT\n", symbol);
                            out.print("         LLGT 15,X'8C'(,15)    ECVT\n");
                            out.print("         LLGT 15,X'CC'(,15)    CSRCTABL\n");
                            out.print("         LLGT 15,X'23C'(,15)   ZVT\n");
                            out.print("         LLGT 15,X'9C'(,15)    FIRST ZVTE (the ZIS)\n");
                            out.print("         LG   15,X'80'(,15)    ZIS STUB VECTOR\n");
                        }
                        case R12 -> {
                            out.printf("%-8.8s LLGT 15,X'2A8'(,12)   Get the (R)LE CAA's RLETask\n", symbol);
                            out.print("         LLGT 15,X'38'(,15)   Get the RLETasks RLEAnchor\n");
                            out.print("         LG   15,X'18'(,15)   Get the Stub Vector \n");
                        }
                        default -> throw new IllegalStateException("unknown dispatch mode " + dispatchMode);
                    }
                    out.printf("         LG   15,X'%02X'(,15)    %s\n", index * 8, symbol);
                    out.print("         BR   15\n");
                } else {
                    out.printf("    stubVector[ZIS_STUB_%-8.8s] = (void*)%s;\n", symbol, functionName);
                }
            }
        }
        if (generateASM) {
            writeLines(out, hlasmEpilog);
        }
        reader.close();
    }

    private static void printHelp(String reason) {
        if (reason != null) {
            System.out.println(reason);
        }
        System.out.println();
        System.out.println("Usage: java com.zossteam.zis.ZISStubGenerator <command> <stub_header> [dispatch_mode]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  command - the utility command (init, asm)");
        System.out.println("    asm - generate a stub file");
        System.out.println("    init - generate the initialization code for the base plugin");
        System.out.println("  sub_header - the header with your stub definitions (usually zisstubs.h)");
        System.out.println("  dispatch_mode - the dispatch mode of the stub routines (only used with the asm command)");
        System.out.println("    r12 - the stub vector is based off of GPR12 (default)");
        System.out.println("    zvte - the stub vector is based off of the ZVTE");
        System.out.println();
    }

    public static void main(String[] args) throws Exception {

        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            printHelp(null);
            return;
        }

        if (args.length < 2) {
            printHelp("Error: too few arguments.");
            return;
        }

        String command = args[0];
        String hFileName = args[1];
        ZISStubGenerator generator = new ZISStubGenerator(hFileName);
        if (command.equalsIgnoreCase("asm")) {
            DispatchMode dispatchMode = DispatchMode.R12;
            if (args.length >= 3) {
                String modeString = args[2];
                if (modeString.equalsIgnoreCase("zvte")) {
                    dispatchMode = DispatchMode.ZVTE;
                } else if (!modeString.equalsIgnoreCase("r12")) {
                    printHelp("Error: unknown dispatch mode " + modeString + ".");
                    return;
                }
            }
            generator.generateCode(System.out, true, dispatchMode);
        } else if (command.equalsIgnoreCase("init")) {
            generator.generateCode(System.out, false, DispatchMode.R12);
        } else {
            printHelp("Error: unknown command " + command + ".");
        }

    }

}

/*
  This program and the accompanying materials are
  made available under the terms of the Eclipse Public License v2.0 which accompanies
  this distribution, and is available at https://www.eclipse.org/legal/epl-v20.html

  SPDX-License-Identifier: EPL-2.0

  Copyright Contributors to the Zowe Project.
 */
