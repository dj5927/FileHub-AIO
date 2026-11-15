package com.nasmount.mobile;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Arrays;
import static org.junit.Assert.*;

public class FileSelectionModelTest {
    @Test public void longPressThenTapAddsIndividualItems() {
        FileSelectionModel<String> selected = new FileSelectionModel<>();
        selected.toggle("a", "movie.mkv");
        selected.toggle("b", "folder");
        assertEquals(2, selected.size());
        assertEquals(Arrays.asList("movie.mkv", "folder"), selected.snapshot());
    }
    @Test public void tappingSelectedItemDeselectsOnlyThatItem() {
        FileSelectionModel<String> selected = new FileSelectionModel<>();
        selected.toggle("a", "one");
        selected.toggle("b", "two");
        selected.toggle("a", "one");
        assertEquals(Arrays.asList("two"), selected.snapshot());
    }
    @Test public void selectAllAndSecondTapClearsAll() {
        FileSelectionModel<String> selected = new FileSelectionModel<>();
        LinkedHashMap<String, String> visible = new LinkedHashMap<>();
        visible.put("a", "one");
        visible.put("b", "two");
        visible.put("c", "three");
        selected.toggle("b", "two");
        selected.selectAll(visible);
        assertEquals(3, selected.size());
        selected.selectAll(visible);
        assertTrue(selected.isEmpty());
    }
    @Test public void selectAllOnEmptyFolderKeepsEmpty() {
        FileSelectionModel<String> selected = new FileSelectionModel<>();
        selected.selectAll(new LinkedHashMap<>());
        assertTrue(selected.isEmpty());
    }
    @Test public void clipboardSnapshotSurvivesFolderSelectionReset() {
        FileSelectionModel<String> selected = new FileSelectionModel<>();
        selected.toggle("a", "movie");
        java.util.List<String> clipboard = selected.snapshot();
        selected.clear();
        selected.toggle("b", "other");
        assertEquals(Arrays.asList("movie"), clipboard);
    }
}
