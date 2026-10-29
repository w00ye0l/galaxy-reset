package com.forholiday.galaxyreset;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RunStateTest {
    // Minimal in-memory preference store: no Android device or shell commands involved.
    static final class Store {
        final Map<String,Object> values=new HashMap<>();
        boolean writable=true;
        SharedPreferences prefs(){
            return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    if(method.getName().equals("edit"))return editor();
                    if(method.getName().startsWith("get"))return values.getOrDefault(args[0],args[1]);
                    throw new UnsupportedOperationException(method.getName());
                });
        }
        SharedPreferences.Editor editor(){
            Map<String,Object> staged=new HashMap<>(values);
            return (SharedPreferences.Editor)Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class},(proxy,method,args)->{
                    String name=method.getName();
                    if(name.equals("clear")){staged.clear();return proxy;}
                    if(name.equals("remove")){staged.remove(args[0]);return proxy;}
                    if(name.startsWith("put")){staged.put((String)args[0],args[1]);return proxy;}
                    if(name.equals("commit")){if(writable){values.clear();values.putAll(staged);}return writable;}
                    throw new UnsupportedOperationException(name);
                });
        }
    }

    @Test public void shutdownClearsRunHistoryAndStartsAtHome(){
        Store store=new Store();
        store.values.put("phase","done");store.values.put("locale","en-US");
        store.values.put("summary","old summary");store.values.put("detail18","old result");
        store.values.put("status18","완료");
        RunState state=new RunState(store.prefs());state.clearHistoryForShutdown();
        assertEquals(Map.of("home_after_shutdown",true),store.values);
        assertTrue(state.homeOnNextLaunch());assertEquals("idle",state.phase());
        assertEquals(0,state.completed());assertEquals("",state.detail(18));
    }

    @Test public void pendingRecoveryBlocksErasingHistory(){
        Store store=new Store();store.values.put("restore",Set.of("com.example.provider"));
        store.values.put("detail18","keep this");Map<String,Object> original=new HashMap<>(store.values);
        try{new RunState(store.prefs()).clearHistoryForShutdown();fail("Recovery must be preserved");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("복구"));}
        assertEquals(original,store.values);
    }

    @Test public void storageFailureDoesNotClaimHistoryCleared(){
        Store store=new Store();store.values.put("phase","done");store.writable=false;
        try{new RunState(store.prefs()).clearHistoryForShutdown();fail("Commit failure must stop shutdown");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("지우지 못"));}
        assertEquals("done",store.values.get("phase"));
    }
    @Test public void pipelineMigrationInvalidatesOldIndicesButPreservesRecoveryAndLocale(){
        Store store=new Store();store.values.put("phase","interrupted");store.values.put("index",15);
        store.values.put("status15","완료");store.values.put("restore",Set.of("widget.app"));store.values.put("locale","ko-KR");
        RunState state=new RunState(store.prefs());state.migratePipeline();
        assertEquals("idle",state.phase());assertEquals(0,state.completed());
        assertEquals(Set.of("widget.app"),state.pending());assertEquals("ko-KR",state.locale());
        state.step(0,"완료","");state.migratePipeline();assertEquals(1,state.completed());
    }

}
