/*
    Copyright other contributors as noted in the AUTHORS file.
                
    This file is part of 0MQ.

    0MQ is free software; you can redistribute it and/or modify it under
    the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation; either version 3 of the License, or
    (at your option) any later version.
            
    0MQ is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.
        
    You should have received a copy of the GNU Lesser General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.zper.base;


import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.List;

import zmq.Msg;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.*;

public class TestZLogManager
{
    private String datadir = ".tmp";

    @Before
    public void setUp()
    {
        reset(2048L, 1000L);
    }

    private void reset(long size, long interval)
    {
        File f = new File(datadir, "new_topic");
        delete(f);
        ZLogManager.instance().config()
                .set("base_dir", datadir)
                .set("segment_size", size)
                .set("flush_interval", interval);
        ZLogManager.instance().get("new_topic").reset();
    }

    public static boolean delete(File path)
    {
        if (!path.exists()) {
            return false;
        }
        boolean ret = true;
        if (path.isDirectory()) {
            for (File f : path.listFiles()) {
                ret = ret && delete(f);
            }
        }
        return ret && path.delete();
    }

    @Test
    public void testNewTopic() throws Exception
    {
        ZLogManager m = ZLogManager.instance();
        ZLog log = m.get("new_topic");

        assertThat(log.start(), is(0L));
        assertThat(log.offset(), is(0L));

        long pos = log.append(new Msg("hello".getBytes()));
        assertThat(pos, is(7L));
        log.flush();

        assertThat(log.offset(), is(7L));
        log.close();
    }

    @Test
    public void testOverflow() throws Exception
    {
        reset(12L, 10000L); // do not flush
        ZLogManager m = ZLogManager.instance();
        ZLog log = m.get("new_topic");

        assertThat(log.start(), is(0L));
        assertThat(log.offset(), is(0L));

        log.append(new Msg("12345".getBytes()));
        assertThat(log.count(), is(1));
        long pos = log.append(new Msg("1234567890".getBytes()));
        assertThat(log.count(), is(2));
        assertThat(pos, is(19L));
        assertThat(log.offset(), is(19L));
        log.close();
    }

    @SuppressWarnings("resource")
    @Test
    public void testRecover() throws Exception
    {
        ZLogManager m = ZLogManager.instance();
        m.config().set("recover", true);
        m.config().set("allow_empty_message", false);

        String path = datadir + "/new_topic/00000000000000000000.dat";
        FileChannel ch = new RandomAccessFile(path, "rw").getChannel();
        MappedByteBuffer buf = ch.map(MapMode.READ_WRITE, 0, m.config().segment_size);
        buf.put((byte) 0);
        buf.put((byte) 5);
        buf.put("12345".getBytes());
        buf.put((byte) 0);
        buf.put((byte) 11);
        buf.put("67890abcdef".getBytes());
        ch.close();

        m.shutdown();

        ZLog log = m.get("new_topic");
        assertThat(log.offset(), is(20L));

        log.append(new Msg("hello".getBytes()));
        assertThat(log.offset(), is(27L));

    }

    @Test
    public void testReadMsg() throws Exception
    {
        reset(9L, 10000L);
        ZLogManager m = ZLogManager.instance();
        ZLog log = m.get("new_topic");

        log.append(new Msg("12345678".getBytes()));
        log.append(new Msg("12345".getBytes()));
        log.append(new Msg("123".getBytes()));
        log.flush();

        long[] offsets = log.offsets();
        assertThat(offsets[0], is(0L));
        assertThat(offsets[1], is(10L));

        List<Msg> msgs = log.readMsg(10L, 1000);
        assertThat(msgs.size(), is(2));
        assertThat(msgs.get(0).size(), is(5));
        assertThat(msgs.get(1).size(), is(3));
    }

    @Test
    public void testRead() throws Exception
    {
        reset(13L, 10000L);
        ZLogManager m = ZLogManager.instance();
        ZLog log = m.get("new_topic");

        log.append(new Msg("12345678".getBytes()));
        log.append(new Msg("12345".getBytes()));
        log.append(new Msg("123".getBytes()));
        log.flush();

        FileChannel ch = log.open(10L);
        assertThat(log.offset(), is(22L));
        assertThat(ch.size(), is(17L));
        ch.close();
    }
}
