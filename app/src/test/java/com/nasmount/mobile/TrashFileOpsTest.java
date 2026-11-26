package com.nasmount.mobile;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TrashFileOpsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void retentionIsInclusiveAndNeverDoesNotExpire(){
        long t=1000000000L,seven=7*86400000L;
        assertFalse(TrashFileOps.expired(t,t+seven-1,7));
        assertTrue(TrashFileOps.expired(t,t+seven,7));
        assertFalse(TrashFileOps.expired(t,t+seven,0));
        assertFalse(TrashFileOps.expired(t,t-1,7));
    }
    @Test public void folderRoundTripPreservesContents()throws Exception{
        File root=temp.newFolder(),src=new File(root,"Documents"),slot=new File(root,"slot");
        assertTrue(src.mkdir());assertTrue(slot.mkdir());
        byte[] data={1,2,3,4};Files.write(new File(src,"test.bin").toPath(),data);
        File moved=new File(slot,"Documents");TrashFileOps.move(src,moved);
        assertFalse(src.exists());assertArrayEquals(data,Files.readAllBytes(new File(moved,"test.bin").toPath()));
        assertEquals(4,TrashFileOps.bytes(moved));TrashFileOps.move(moved,src);
        assertArrayEquals(data,Files.readAllBytes(new File(src,"test.bin").toPath()));
    }
    @Test public void restoreNeverOverwritesExistingFile()throws Exception{
        File src=temp.newFile(),dst=temp.newFile();Files.write(src.toPath(),new byte[]{1});Files.write(dst.toPath(),new byte[]{2});
        assertThrows(FileAlreadyExistsException.class,()->TrashFileOps.move(src,dst));
        assertArrayEquals(new byte[]{1},Files.readAllBytes(src.toPath()));
        assertArrayEquals(new byte[]{2},Files.readAllBytes(dst.toPath()));
    }
    @Test public void malformedAndReservedPathsAreRejected()throws Exception{
        for(String bad:new String[]{"../other","/foo","a/../x","a//b","a\\b",".filehub-aio-trash-direct/x"})
            assertThrows(IOException.class,()->TrashFileOps.safeRelative(bad));
        assertEquals("Pictures/사진.jpg",TrashFileOps.safeRelative("Pictures/사진.jpg"));
        assertEquals("a/b.txt",LocalTrash.relativeDocument("primary:","primary:a/b.txt"));
        assertEquals("file.txt",LocalTrash.relativeDocument("ABCD:docs","ABCD:docs/file.txt"));
        assertThrows(IOException.class,()->LocalTrash.relativeDocument("ABCD:docs","ABCD:docs2/file.txt"));
        assertThrows(IOException.class,()->TrashFileOps.slotId("../other"));
        String id=UUID.randomUUID().toString();assertEquals(id,TrashFileOps.slotId(id));
    }
    @Test public void purgeOnlyRemovesSpecifiedSlot()throws Exception{
        File slot=temp.newFolder(),outside=temp.newFile();Files.write(outside.toPath(),new byte[]{9});
        File nested=new File(slot,"nested");assertTrue(nested.mkdir());Files.write(new File(nested,"x").toPath(),new byte[]{1});
        TrashFileOps.purge(slot);assertFalse(slot.exists());assertArrayEquals(new byte[]{9},Files.readAllBytes(outside.toPath()));
    }
}
