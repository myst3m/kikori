/*
 *   Kikori
 *
 *   Copyright (c) Tsutomu Miyashita. All rights reserved.
 *
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 */

/* This sample works only on JDK 9+ */

/* How to use

   1.  Compile

   $ javac -cp kikori.jar D6t44lJava.java

   2. Put the class file to the directory on CLASSPATH.
   Then, load the class by using (load-java-module "D6t44lJava")

*/

package kikori;

import java.util.*;
import java.io.*;

import kikori.interop.Edge;
import kikori.interop.IModule;

public class D6t44lJava implements IModule {
    Byte[] op = {0x4c};
    HashMap<String, List> result = new HashMap<String, List>();

    public HashMap measure(Edge edge) {
	
	edge.write(Arrays.asList(op));
	byte[] raw = edge.read(35);

	if (34 < raw.length) {

	    ArrayList<Integer> p = new ArrayList<Integer>();

	    for (int i=0; i < 17; i++) {
		p.add((0x00ff & raw[2*i]) | ((0x00ff & (raw[2*i+1])) << 8));
	    }
	    result.clear();
	    result.put("PTAT", p.subList(0,1));
	    result.put("PX", p.subList(1, 17));
	} 
	
	return result;
    }


    public Edge init(Edge edge) {
	System.out.println("Init");
    	return edge;
    };
    
    public HashMap read(Edge edge) {
	System.out.println("Read");
	return measure(edge);
    };

    public HashMap write(Edge edge, List data) {
	HashMap result = new HashMap();
	result.put("result", "success");
	return result;
    };

    public void close(Edge edge) {
	System.out.println("Close");	
    };
}

