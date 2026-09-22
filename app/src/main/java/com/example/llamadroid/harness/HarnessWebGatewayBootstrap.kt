package com.example.llamadroid.harness

/** Injected only into a successfully authenticated, same-origin HTML response. */
internal object HarnessWebGatewayBootstrap {
    fun patch(html: String): String {
        if ("__ADT_AUTHENTICATED_GATEWAY__" in html) return html
        val script = """
            <script>(function(){
              globalThis.__ADT_AUTHENTICATED_GATEWAY__=true;
              if(!Promise.withResolvers)Promise.withResolvers=function(){var resolve,reject;var promise=new Promise(function(a,b){resolve=a;reject=b});return {promise:promise,resolve:resolve,reject:reject}};
              try{var c=globalThis.crypto;if(c&&c.getRandomValues&&!c.randomUUID)c.randomUUID=function(){var b=new Uint8Array(16);c.getRandomValues(b);b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;var s='';for(var j=0;j<16;j++)s+=('0'+b[j].toString(16)).slice(-2);return s.slice(0,8)+'-'+s.slice(8,12)+'-'+s.slice(12,16)+'-'+s.slice(16,20)+'-'+s.slice(20)};}catch(_){}
            })();</script>
        """.trimIndent()
        // Install before any synchronous upstream boot script can choose its persistence mode.
        val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
        val offset = head?.range?.last?.plus(1) ?: 0
        return html.substring(0, offset) + script + html.substring(offset)
    }
}
