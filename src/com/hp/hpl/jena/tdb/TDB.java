/*
 * (c) Copyright 2008, 2009 Hewlett-Packard Development Company, LP}
 * All rights reserved.
 * [See end of file]
 */

package com.hp.hpl.jena.tdb;

import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.impl.RDFReaderFImpl;

import com.hp.hpl.jena.graph.Graph;
import com.hp.hpl.jena.graph.Node;

import com.hp.hpl.jena.sparql.ARQConstants;
import com.hp.hpl.jena.sparql.core.DatasetGraph;
import com.hp.hpl.jena.sparql.core.assembler.AssemblerUtils;
import com.hp.hpl.jena.sparql.engine.main.StageGenBasicPattern;
import com.hp.hpl.jena.sparql.engine.main.StageGenerator;
import com.hp.hpl.jena.sparql.engine.optimizer.StageGenOptimizedBasicPattern;
import com.hp.hpl.jena.sparql.util.Context;
import com.hp.hpl.jena.sparql.util.Symbol;

import com.hp.hpl.jena.query.ARQ;
import com.hp.hpl.jena.query.Dataset;

import com.hp.hpl.jena.tdb.assembler.VocabTDB;
import com.hp.hpl.jena.tdb.base.loader.NTriplesReader2;
import com.hp.hpl.jena.tdb.lib.Sync;
import com.hp.hpl.jena.tdb.solver.OpExecutorTDB;
import com.hp.hpl.jena.tdb.solver.QueryEngineTDB;
import com.hp.hpl.jena.tdb.solver.StageGeneratorDirectTDB;
import com.hp.hpl.jena.tdb.solver.StageGeneratorGeneric;
import com.hp.hpl.jena.tdb.store.DatasetGraphTDB;
import com.hp.hpl.jena.tdb.sys.Metadata;
import com.hp.hpl.jena.tdb.sys.SystemTDB;


public class TDB
{
    // Internal logging
    private static final Logger log = LoggerFactory.getLogger(TDB.class) ;
    
    /** Logger for general information */ 
    public static final Logger logInfo = LoggerFactory.getLogger("com.hp.hpl.jena.tdb.info") ;
    
    /** Logger for execution information */
    public static final Logger logExec = LoggerFactory.getLogger("com.hp.hpl.jena.tdb.exec") ;
    
    public final static String namespace = "http://jena.hpl.hp.com/2008/tdb#" ;

    /** Symbol to use the union of named graphs as the default graph of a query */ 
    public static final Symbol symUnionDefaultGraph          = SystemTDB.allocSymbol("unionDefaultGraph") ;
    
    /** Symbol to enable logging of execution.  Must also set log4j, or other logging system,
     * for logger "com.hp.hpl.jena.tdb.exec"
     * e.g. log4j.properties -- log4j.logger.com.hp.hpl.jena.tdb.exec=INFO
     */
    public static final Symbol symLogExec           = SystemTDB.allocSymbol("logExec") ;

    /** Set or unset execution logging - logging is to logger "com.hp.hpl.jena.tdb.exec" at level INFO.
     * An appropriate logging configuration is also required.
     */
    public static void setExecutionLogging(boolean state)
    {
        TDB.getContext().set(TDB.symLogExec, state) ;
        if ( ! logExec.isInfoEnabled() )
            log.warn("Attempt to enable execution logging but the logger is not logging at level info") ;
    }
    
    public static Context getContext()     { return ARQ.getContext() ; }  
    
    // Called on assembler loading.
    /** TDB System initialization - normally, this is not explicitly called because
     * all routes to use TDB will cause initialization to occur.  However, calling it
     * repeatedly is safe and low cost.
     */
    public static void init() { }
    
//    /** Sync a TDB synchronizable object (model, graph daatset). Do nothing otherwise */
//    public static void sync(Object object) { sync(object, true) ; }

    /** Sync a TDB synchronizable object (model, graph daatset). Do nothing otherwise */
    public static void sync(Model model)
    {
        sync(model.getGraph()) ;
    }
    
    /** Sync a TDB synchronizable object (model, graph daatset). Do nothing otherwise */
    public static void sync(Graph graph)
    {
        sync(graph, true) ;
    }

    /** Sync a TDB synchronizable object (model, graph daatset). Do nothing otherwise */
    public static void sync(Dataset dataset)
    { 
        DatasetGraph ds = dataset.asDatasetGraph() ;
        sync(ds) ;
    }
    
    /** Sync a TDB synchronizable object (model, graph daatset). Do nothing otherwise */
    public static void sync(DatasetGraph dataset)
    { 
        if ( dataset instanceof DatasetGraphTDB )
            sync(dataset, true) ;
        else
        {
            // May be a general purpose datsset with TDB objects in it.
            Iterator<Node> iter = dataset.listGraphNodes() ;
            for ( ; iter.hasNext() ; )
            {
                Node n = iter.next();
                Graph g = dataset.getGraph(n) ;
                sync(g) ;
            }
        }
    }

    
    /** Sync a TDB synchronizable object (model, graph daatset). 
     *  If force is true, synchronize as much as possible (e.g. file metadata)
     *  else make a reasonable attenpt at synchronization but does not gauarantee disk state. 
     * Do nothing otherwise */
    private static void sync(Object object, boolean force)
    {
        if ( object instanceof Sync )
            ((Sync)object).sync(force) ;
    }
    
    static { initWorker() ; }
    
    private static boolean initialized = false ;
    private static synchronized void initWorker()
    {
        if ( initialized )
            return ;
        initialized = true ;

        //TDB.getContext().set(??,??) ;
        
        // TDB uses a custom OpCompiler.
        
        AssemblerUtils.init() ;     // ARQ initialization.
        VocabTDB.init();
        QueryEngineTDB.register() ;
        
        // XXX Really need to sort this out!
        TDB.getContext().set(ARQ.filterPlacement, false) ;
        
        wireIntoExecution() ;
        
        // Override N-TRIPLES
        String bulkLoaderClass = NTriplesReader2.class.getName() ;
        RDFReaderFImpl.setBaseReaderClassName("N-TRIPLES", bulkLoaderClass) ;
        RDFReaderFImpl.setBaseReaderClassName("N-TRIPLE", bulkLoaderClass) ;
        
        if ( log.isDebugEnabled() )
            log.debug("\n"+ARQ.getContext()) ;
    }
    
    private static void wireIntoExecution()
    {
        // Globally change the stage generator to intercept BGP on TDB
        StageGenerator orig = (StageGenerator)ARQ.getContext().get(ARQ.stageGenerator) ;
        
        if ( orig instanceof StageGenBasicPattern || orig instanceof StageGenOptimizedBasicPattern )
            // ARQ base.  Cause chaos by using the new version.
            orig = new StageGeneratorGeneric() ;
        
        // Wire in the TDB stage generator which will make TDB work whether
        // or not the TDB executor is used.
        StageGenerator stageGenerator = new StageGeneratorDirectTDB(orig) ;
        ARQ.getContext().set(ARQ.stageGenerator, stageGenerator) ;

        // Wire in the new OpExecutor.  This is normal way to execute.
        ARQ.getContext().set(ARQConstants.sysOpExecutorFactory, OpExecutorTDB.altFactory) ;
    }
    
    // ---- Static constandts read by modVersion 
    /** The root package name for TDB */   
    public static final String PATH = "com.hp.hpl.jena.tdb";

    // The names known to ModVersion : "NAME", "VERSION", "BUILD_DATE"
    
    public static final String NAME = "TDB" ;
    
    /** The full name of the current TDB version */   
    public static final String VERSION = Metadata.get(PATH+".version", "unknown") ;
//   
//    /** The major version number for this release of TDB (ie '2' for TDB 2.0) */
//    public static final String MAJOR_VERSION = "@version-major@";
//   
//    /** The minor version number for this release of TDB (ie '0' for TDB 2.0) */
//    public static final String MINOR_VERSION = "@version-minor@";
//   
//    /** The version status for this release of SDB (eg '-beta1' or the empty string) */
//    public static final String VERSION_STATUS = "@version-status@";
//   
    /** The date and time at which this release was built */   
    public static final String BUILD_DATE = Metadata.get(PATH+".build.datetime", "unset") ;
}

/*
 * (c) Copyright 2008, 2009 Hewlett-Packard Development Company, LP
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */