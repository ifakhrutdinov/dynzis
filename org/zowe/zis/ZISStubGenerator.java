package org.zowe.zis;

import java.io.IOException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.PrintStream;

public class ZISStubGenerator {

  /* 
     The purpose of the stubs is to be able to build ZIS plugins that do NOT 
     statically link zowe-common-c and ZIS base code into the plugin.

     Reserving some low slots for version and internal purposes 

      
  */

    String hFileName;

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
                    ".* The HLASM GOFF option is needed to assemble this program"};

    private static final String[] hlasmEpilog =
            {
                    "         EJECT",
                    "ZISSTUBS CSECT ,",
                    "         END"};

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
                    out.printf("    stubVector[ZIS_STUB_%-8.8s] = (void*)%s;\n",
                            symbol, functionName);
                }
            }
        }
        if (generateASM) {
            writeLines(out, hlasmEpilog);
        }
        reader.close();
    }

    // java com.zossteam.zis.ZISStubGenerator asm zisstubs.h
    // java com.zossteam.zis.ZISStubGenerator init zisstubs.h
    public static void main(String[] args) throws Exception {
        String command = args[0];
        String hFileName = args[1];
        ZISStubGenerator generator = new ZISStubGenerator(hFileName);
        if (command.equalsIgnoreCase("asm")) {
            generator.generateCode(System.out, true, DispatchMode.R12);
        } else if (command.equalsIgnoreCase("init")) {
            generator.generateCode(System.out, false, DispatchMode.R12);
        }
    }

}
