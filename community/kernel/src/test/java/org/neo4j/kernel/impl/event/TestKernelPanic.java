/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.event;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Rule;
import org.junit.Test;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.event.ErrorState;
import org.neo4j.graphdb.event.KernelEventHandler;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.test.EphemeralFileSystemRule;
import org.neo4j.test.TargetDirectory;

public class TestKernelPanic
{
    private static final int COUNT = 100000;

    @Test
    public void simplePerfTest() throws Exception
    {
    	File dir = TargetDirectory.forTest(getClass()).cleanDirectory("foobar");    			
        final GraphDatabaseService db = new GraphDatabaseFactory().newEmbeddedDatabase( dir.getAbsolutePath() );

        ExecutorService es = Executors.newFixedThreadPool( 6 );


        final Random r = new Random();
        final List<Node> nodes = new ArrayList<>( 300 );

        try( org.neo4j.graphdb.Transaction tx = db.beginTx() )
        {
            for ( int i = 0; i < 300; i++ )
            {
                nodes.add( db.createNode() );
            }
            tx.success();
        }

        long startedAt = System.currentTimeMillis();
        final CountDownLatch cdl = new CountDownLatch( COUNT );

        for ( int i = 0; i < COUNT; i++ )
        {
            es.execute( new Runnable()
            {
                @Override
                public void run()
                {
                    try ( org.neo4j.graphdb.Transaction tx = db.beginTx() )
                    {
                        for ( int j = 0; j < 400; j++ )
                        {
                            Node node1 = nodes.get( r.nextInt( 100 ) );
                            Node node2 = nodes.get( r.nextInt( 100 ) );
                            node1.createRelationshipTo( node2, DynamicRelationshipType.withName( "foo" ) );
                        }
                        tx.success();
                    }
                    finally
                    {
                        cdl.countDown();
                    }
                }
            } );
        }
        cdl.await();
        es.shutdown();
        System.out.println("Took " + (System.currentTimeMillis() - startedAt));
    }
	
    @Test( timeout = 10000 )
    public void panicTest() throws Exception
    {
//        BufferingLogger logger = new BufferingLogger();
//        GraphDatabaseService graphDb = new TestGraphDatabaseFactory().setLogging(
//                new SingleLoggingService( logger ) ).newImpermanentDatabase();
//
//        Panic panic = new Panic();
//        graphDb.registerKernelEventHandler( panic );
//
//        org.neo4j.graphdb.Transaction gdbTx = graphDb.beginTx();
//        TransactionManager txMgr = ((GraphDatabaseAPI)graphDb).getDependencyResolver()
//                .resolveDependency( TransactionManager.class );
//        Transaction tx = txMgr.getTransaction();
//
//        graphDb.createNode();
//        try
//        {
//            gdbTx.success();
//            gdbTx.finish();
//            fail( "Should fail" );
//        }
//        catch ( Exception t )
//        {
//            // It's okay, we expected this.
//            // Now just wait until we observe the kernel panicking:
//            //noinspection StatementWithEmptyBody
//            while ( !panic.panic );
//        }
//        finally
//        {
//            graphDb.unregisterKernelEventHandler( panic );
//        }
//
//        try
//        {
//            assertTrue( panic.panic );
//            assertThat("Log didn't contain expected string",
//                    logger.toString(), containsString("at org.neo4j.kernel.impl.event.TestKernelPanic.panicTest"));
//        }
//        finally
//        {
//            graphDb.shutdown();
//        }
//    }
//
//    @Test
//    public void shouldPanicOnApplyTransactionFailure() throws Exception
//    {
//        // GIVEN
//        TestGraphDatabaseFactory factory = new TestGraphDatabaseFactory();
//        factory.setTransactionInterceptorProviders( asList( interceptorProviderThatBreaksStuff() ) );
//        GraphDatabaseAPI db = (GraphDatabaseAPI) factory.newImpermanentDatabaseBuilder()
//                .setConfig( GraphDatabaseSettings.intercept_deserialized_transactions.name(), TRUE.toString() )
//                .setConfig( TransactionInterceptorProvider.class.getSimpleName() + ".breaker", TRUE.toString() )
//                .newGraphDatabase();
//        XaDataSourceManager dsManager = db.getDependencyResolver().resolveDependency( XaDataSourceManager.class );
//        XaDataSource ds = dsManager.getXaDataSource( NeoStoreXaDataSource.DEFAULT_DATA_SOURCE_NAME );
//
//        // WHEN
//        try
//        {
//            ds.applyCommittedTransaction( 2, simpleTransaction() );
//            fail( "Should have failed" );
//        }
//        catch ( BreakageException e )
//        {   // Good
//        }
//
//        // THEN
//        assertNotOk( beginTransaction( db ) );
//        assertNotOk( applyTransaction( ds ) );
    }

    private void assertNotOk( Callable<Void> callable )
    {
        try
        {
            callable.call();
            fail( "Should have failed saying that tm not OK");
        }
        catch ( Exception e )
        {   // Good
            assertTrue( someExceptionContainsMessage( e, "Kernel has encountered some problem" ) );
        }
    }

    private boolean someExceptionContainsMessage( Throwable e, String string )
    {
        while ( e != null )
        {
            if ( e.getMessage().contains( string ) )
            {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }



    private Callable<Void> beginTransaction( final GraphDatabaseService db )
    {
        return new Callable<Void>()
        {
            @Override
            public Void call() throws Exception
            {
                db.beginTx();
                return null;
            }
        };
    }

    public final @Rule EphemeralFileSystemRule fs = new EphemeralFileSystemRule();

    private static class Panic implements KernelEventHandler
    {
        volatile boolean panic = false;

        @Override
        public void beforeShutdown()
        {
        }

        @Override
        public Object getResource()
        {
            return null;
        }

        @Override
        public void kernelPanic( ErrorState error )
        {
            panic = true;
        }

        @Override
        public ExecutionOrder orderComparedTo( KernelEventHandler other )
        {
            return null;
        }
    }
}
