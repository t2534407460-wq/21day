package com.twentyone.rhythm

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateParsingTest {
    private fun release(url:String="https://github.com/${AppUpdates.REPOSITORY}/releases/download/v0.3.0/21day-0.3.0-debug.apk")=JSONObject().put("tag_name","v0.3.0").put("assets",org.json.JSONArray().put(JSONObject().put("name","21day-0.3.0-debug.apk").put("browser_download_url",url).put("size",1234).put("digest","sha256:"+"a".repeat(64))))
    @Test fun newerVersionsUseNumericOrder(){assertTrue(AppUpdates.newer("0.10.0","0.2.0"));assertFalse(AppUpdates.newer("0.2.0","0.2.0"));assertFalse(AppUpdates.newer("0.1.9","0.2.0"));assertFalse(AppUpdates.newer("0.3.0-beta","0.2.0"))}
    @Test fun onlyPublishedNewerReleaseIsOffered(){assertNotNull(AppUpdates.parse(release(),"0.2.0"));assertNull(AppUpdates.parse(release(),"0.3.0"));assertNull(AppUpdates.parse(release().put("prerelease",true),"0.2.0"))}
    @Test fun foreignRepositoryAndInsecureDownloadAreRejected(){
        for(url in listOf("http://github.com/${AppUpdates.REPOSITORY}/releases/download/v0.3.0/app.apk","https://github.com/other/project/releases/download/v0.3.0/app.apk","https://example.com/app.apk")){
            try{AppUpdates.parse(release(url),"0.2.0");fail("must reject $url")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun missingDigestIsRejected(){val value=release();value.getJSONArray("assets").getJSONObject(0).remove("digest");try{AppUpdates.parse(value,"0.2.0");fail("missing digest")}catch(_:IllegalArgumentException){}}
}
