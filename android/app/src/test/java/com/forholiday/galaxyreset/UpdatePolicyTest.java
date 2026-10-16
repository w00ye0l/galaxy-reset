package com.forholiday.galaxyreset;

import java.net.URI;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdatePolicyTest {
    @Test public void onlyStrictlyNewerVersionsAreOffered(){
        assertTrue(UpdatePolicy.newer(14,15));assertFalse(UpdatePolicy.newer(15,15));assertFalse(UpdatePolicy.newer(15,14));
    }
    @Test public void acceptsOnlyThisRepositoryReleaseAssets(){
        String base="https://github.com/w00ye0l/galaxy-reset/releases/download/app-v1.1.11/";
        assertTrue(UpdatePolicy.releaseAsset(base+"galaxy-reset.apk","galaxy-reset.apk"));
        for(String url:new String[]{base+"other.apk",base.replace("https:","http:")+"galaxy-reset.apk",
            base.replace("w00ye0l","someoneelse")+"galaxy-reset.apk",base.replace("github.com","github.com.example.org")+"galaxy-reset.apk",
            base.replace("github.com","github.com@evil.example")+"galaxy-reset.apk",base+"%2e%2e/galaxy-reset.apk"})
            assertFalse(url,UpdatePolicy.releaseAsset(url,"galaxy-reset.apk"));
    }
    @Test public void rejectsNonGithubRedirectsAndInsecureDownloads(){
        for(String host:new String[]{"github.com","api.github.com","release-assets.githubusercontent.com","objects.githubusercontent.com"})
            assertTrue(UpdatePolicy.downloadHost(URI.create("https://"+host+"/asset")));
        for(String url:new String[]{"http://github.com/asset","https://example.org/asset","file:///tmp/file","https://github.com:8443/asset","https://user@github.com/asset"})
            assertFalse(url,UpdatePolicy.downloadHost(URI.create(url)));
    }
    @Test public void requiresFullSha256Digest(){
        assertTrue(UpdatePolicy.validHash("a".repeat(64)));
        for(String hash:new String[]{null,"","a".repeat(63),"g".repeat(64),"../something"})assertFalse(UpdatePolicy.validHash(hash));
    }
    @Test public void rejectsDifferentPackagesAndMetadataVersionMismatch(){
        String pkg="com.forholiday.galaxyreset";
        assertTrue(UpdatePolicy.archiveMatches(pkg,14,15,pkg,15));
        assertFalse(UpdatePolicy.archiveMatches(pkg,14,15,"com.other.app",15));
        assertFalse(UpdatePolicy.archiveMatches(pkg,14,15,pkg,16));
        assertFalse(UpdatePolicy.archiveMatches(pkg,15,15,pkg,15));
        assertFalse(UpdatePolicy.archiveMatches(pkg,16,15,pkg,15));
    }
    @Test public void onlyIdenticalNonemptyPublisherSignerSetsPass(){
        assertTrue(UpdatePolicy.sameSigners(Set.of("publisher-A"),Set.of("publisher-A")));
        assertFalse(UpdatePolicy.sameSigners(Set.of("publisher-A"),Set.of("publisher-B")));
        assertFalse(UpdatePolicy.sameSigners(Set.of(),Set.of()));
        assertFalse(UpdatePolicy.sameSigners(Set.of("publisher-A"),Set.of("publisher-A","publisher-B")));
    }
}
