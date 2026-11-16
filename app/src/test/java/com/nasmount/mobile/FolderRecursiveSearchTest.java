package com.nasmount.mobile;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class FolderRecursiveSearchTest {
    static class N{
        final String name;final List<N> children=new ArrayList<>();
        N(String s,N... kids){name=s;children.addAll(Arrays.asList(kids));}
    }
    private static FolderRecursiveSearch.Adapter<N> adapter(){
        return new FolderRecursiveSearch.Adapter<N>(){
            public List<N> list(N node){return node.children;}
            public boolean directory(N node){return !node.children.isEmpty();}
            public String name(N node){return node.name;}
            public String id(N node){return ""+System.identityHashCode(node);}
        };
    }
    @Test public void searchesAllDescendantsAndKeepsPaths(){
        N tree=new N("root",new N("novels",new N("Dragon.epub"),
                new N("more",new N("Dragon.txt"))),new N("outside.txt"));
        FolderRecursiveSearch.Result<N> found=FolderRecursiveSearch.find(
                tree,adapter(),"dragon",true,()->false);
        assertEquals(2,found.hits.size());
        assertEquals("novels/Dragon.epub",found.hits.get(0).path);
        assertEquals("novels/more/Dragon.txt",found.hits.get(1).path);
    }
    @Test public void cancelStopsBeforeReadingFolder(){
        N root=new N("root",new N("child",new N("a.txt")));
        FolderRecursiveSearch.Result<N> result=FolderRecursiveSearch.find(
                root,adapter(),"a",true,()->true);
        assertTrue(result.cancelled);
        assertEquals(0,result.hits.size());
    }
    @Test public void hiddenDirectoriesAreExcludedByDefault(){
        N root=new N("root",new N(".hidden",new N("find.txt")),
                new N("visible",new N("find2.txt")));
        assertEquals(1,FolderRecursiveSearch.find(root,adapter(),
                "find",false,()->false).hits.size());
        assertEquals(2,FolderRecursiveSearch.find(root,adapter(),
                "find",true,()->false).hits.size());
    }
}
