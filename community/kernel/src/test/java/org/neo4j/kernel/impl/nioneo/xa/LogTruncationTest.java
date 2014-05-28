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
package org.neo4j.kernel.impl.nioneo.xa;

import static java.util.Arrays.asList;
import static junit.framework.TestCase.assertNull;
import static org.junit.Assert.assertEquals;
import static org.neo4j.kernel.impl.nioneo.store.DynamicRecord.dynamicRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.neo4j.kernel.api.index.SchemaIndexProvider;
import org.neo4j.kernel.impl.nioneo.store.IndexRule;
import org.neo4j.kernel.impl.nioneo.store.LabelTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.NeoStoreRecord;
import org.neo4j.kernel.impl.nioneo.store.NodeRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyKeyTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipTypeTokenRecord;
import org.neo4j.kernel.impl.nioneo.xa.command.Command;
import org.neo4j.kernel.impl.nioneo.xa.command.PhysicalLogNeoXaCommandReaderV1;
import org.neo4j.kernel.impl.transaction.xaframework.CommandSerializer;
import org.neo4j.kernel.impl.transaction.xaframework.LogEntryWriter;
import org.neo4j.kernel.impl.transaction.xaframework.LogEntryWriterv1;
import org.neo4j.kernel.impl.transaction.xaframework.LogPosition;
import org.neo4j.kernel.impl.transaction.xaframework.ReadableLogChannel;
import org.neo4j.kernel.impl.transaction.xaframework.WritableLogChannel;

/**
 * At any point, a power outage may stop us from writing to the log, which means that, at any point, all our commands
 * need to be able to handle the log ending mid-way through reading it.
 */
public class LogTruncationTest
{
    private final InMemoryLogBuffer inMemoryBuffer = new InMemoryLogBuffer();
    private final PhysicalLogNeoXaCommandReaderV1 reader = new PhysicalLogNeoXaCommandReaderV1();
    private final LogEntryWriter writer = new LogEntryWriterv1(inMemoryBuffer, new CommandSerializer(inMemoryBuffer));

    /** Stores all known commands, and an arbitrary set of different permutations for them */
    private final Map<Class<?>, Command[]> permutations = new HashMap<>();

    {
        permutations.put( Command.NeoStoreCommand.class, new Command[]{
            new Command.NeoStoreCommand().init( new NeoStoreRecord() )
        });

        permutations.put( Command.NodeCommand.class, new Command[]{
            new Command.NodeCommand().init( new NodeRecord( 12l, false, 13l, 13l ), new NodeRecord( 0,false, 0,0 ) )
        });
        permutations.put( Command.RelationshipCommand.class, new Command[]{
            new Command.RelationshipCommand().init( new RelationshipRecord( 1l, 2l, 3l, 4 ) )
        });
        permutations.put( Command.PropertyCommand.class, new Command[]{
            new Command.PropertyCommand().init(
                new PropertyRecord( 1, new NodeRecord(12l, false, 13l, 13) ),
                new PropertyRecord( 1, new NodeRecord(12l, false, 13l, 13) ) )
        });
        permutations.put( Command.RelationshipGroupCommand.class, new Command[]{
            new Command.LabelTokenCommand().init( new LabelTokenRecord( 1 ) )
        });

        permutations.put( Command.SchemaRuleCommand.class, new Command[]{
            new Command.SchemaRuleCommand().init(
                asList( dynamicRecord( 1l, false, true, -1l, 1, "hello".getBytes() )),
                asList( dynamicRecord( 1l, true, true, -1l, 1, "hello".getBytes() )), new IndexRule(1, 3, 4, new SchemaIndexProvider.Descriptor("1", "2"), null))});

        permutations.put( Command.RelationshipTypeTokenCommand.class, new Command[]{
            new Command.RelationshipTypeTokenCommand().init( new RelationshipTypeTokenRecord( 1 ) )
        });
        permutations.put( Command.PropertyKeyTokenCommand.class, new Command[]{
            new Command.PropertyKeyTokenCommand().init( new PropertyKeyTokenRecord( 1 ) )
        });
        permutations.put( Command.LabelTokenCommand.class, new Command[]{
            new Command.LabelTokenCommand().init( new LabelTokenRecord( 1 ) )
        });
    }

    @Test
    public void testSerializationInFaceOfLogTruncation() throws Exception
    {
        for ( Command cmd : enumerateCommands() )
        {
            assertHandlesLogTruncation( cmd );
        }
    }

    private Iterable<Command> enumerateCommands()
    {
        // We use this reflection approach rather than just iterating over the permutation map to force developers
        // writing new commands to add the new commands to this test. If you came here because of a test failure from
        // missing commands, add all permutations you can think of of the command to the permutations map in the
        // beginning of this class.

        List<Command> commands = new ArrayList<>();
        for ( Class<?> cmd : Command.class.getClasses() )
        {
            if( Command.class.isAssignableFrom( cmd ) )
            {
                if(permutations.containsKey( cmd ))
                {
                    commands.addAll( asList( permutations.get( cmd ) ) );
                }
                else
                {
                    throw new AssertionError( "Unknown command type: " + cmd + ", please add missing instantiation to " +
                            "test serialization of this command." );
                }
            }
        }
        return commands;
    }

    private void assertHandlesLogTruncation( Command cmd ) throws IOException
    {
        inMemoryBuffer.reset();

        writer.writeCommandEntry( cmd );

        int bytesSuccessfullyWritten = inMemoryBuffer.bytesWritten();

        try
        {
            assertEquals( cmd, reader.read( inMemoryBuffer ) );
        }
        catch(Exception e)
        {
            throw new AssertionError( "Failed to deserialize " + cmd.toString() + ", because: ", e );
        }

        bytesSuccessfullyWritten--;

        while(bytesSuccessfullyWritten --> 0)
        {
            inMemoryBuffer.reset();
            writer.writeCommandEntry( cmd );
            inMemoryBuffer.truncateTo( bytesSuccessfullyWritten );

            Command deserialized = reader.read( inMemoryBuffer );

            assertNull( "Deserialization did not detect log truncation! Record: " + cmd +
                        ", deserialized: " + deserialized, deserialized );
        }
    }

    public class InMemoryLogBuffer implements ReadableLogChannel, WritableLogChannel
    {
        private byte[] bytes = new byte[1000];
        private int writeIndex;
        private int readIndex;
        private ByteBuffer bufferForConversions = ByteBuffer.wrap( new byte[100] );

        public InMemoryLogBuffer()
        {
        }

        public void reset()
        {
            writeIndex = readIndex = 0;
        }

        public void truncateTo( int bytes )
        {
            writeIndex = bytes;
        }

        public int bytesWritten()
        {
            return writeIndex;
        }

        private void ensureArrayCapacityPlus( int plus )
        {
            while ( writeIndex+plus > bytes.length )
            {
                byte[] tmp = bytes;
                bytes = new byte[bytes.length*2];
                System.arraycopy( tmp, 0, bytes, 0, tmp.length );
            }
        }

        private WritableLogChannel flipAndPut()
        {
            ensureArrayCapacityPlus( bufferForConversions.limit() );
            System.arraycopy( bufferForConversions.flip().array(), 0, bytes, writeIndex,
                              bufferForConversions.limit() );
            writeIndex += bufferForConversions.limit();
            return this;
        }

        @Override
        public WritableLogChannel put( byte b ) throws IOException
        {
            ensureArrayCapacityPlus( 1 );
            bytes[writeIndex++] = b;
            return this;
        }

        @Override
        public WritableLogChannel putShort( short s ) throws IOException
        {
            ((ByteBuffer) bufferForConversions.clear()).putShort( s );
            return flipAndPut();
        }

        @Override
        public WritableLogChannel putInt( int i ) throws IOException
        {
            ((ByteBuffer) bufferForConversions.clear()).putInt( i );
            return flipAndPut();
        }

        @Override
        public WritableLogChannel putLong( long l ) throws IOException
        {
            ((ByteBuffer) bufferForConversions.clear()).putLong( l );
            return flipAndPut();
        }

        @Override
        public WritableLogChannel putFloat( float f ) throws IOException
        {
            ((ByteBuffer) bufferForConversions.clear()).putFloat( f );
            return flipAndPut();
        }

        @Override
        public WritableLogChannel putDouble( double d ) throws IOException
        {
            ((ByteBuffer) bufferForConversions.clear()).putDouble( d );
            return flipAndPut();
        }

        @Override
        public WritableLogChannel put( byte[] bytes, int length ) throws IOException
        {
            ensureArrayCapacityPlus( bytes.length );
            System.arraycopy( bytes, 0, this.bytes, writeIndex, bytes.length );
            writeIndex += bytes.length;
            return this;
        }

        @Override
        public WritableLogChannel put( char[] chars, int length ) throws IOException
        {
            ensureConversionBufferCapacity( chars.length*2 );
            bufferForConversions.clear();
            for ( char ch : chars )
            {
                bufferForConversions.putChar( ch );
            }
            return flipAndPut();
        }

        private void ensureConversionBufferCapacity( int length )
        {
            if ( bufferForConversions.capacity() < length )
            {
                bufferForConversions = ByteBuffer.wrap( new byte[length*2] );
            }
        }

        @Override
        public void force() throws IOException
        {
        }

        @Override
        public void close() throws IOException
        {
        }

        public int read( ByteBuffer dst ) throws IOException
        {
            if ( readIndex >= writeIndex )
            {
                return -1;
            }

            int actualLengthToRead = Math.min( dst.limit(), writeIndex-readIndex );
            try
            {
                dst.put( bytes, readIndex, actualLengthToRead );
                return actualLengthToRead;
            }
            finally
            {
                readIndex += actualLengthToRead;
            }
        }

		@Override
		public boolean hasMoreData() throws IOException {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public byte get() throws IOException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public short getShort() throws IOException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public int getInt() throws IOException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public long getLong() throws IOException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public float getFloat() throws IOException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public double getDouble() throws IOException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public void get(byte[] bytes, int length) throws IOException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void get(char[] chars, int length) throws IOException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public LogPosition getCurrentPosition() throws IOException {
			// TODO Auto-generated method stub
			return null;
		}
    }
}
