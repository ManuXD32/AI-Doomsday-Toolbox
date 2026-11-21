package com.example.llamadroid.chat.cards.map

import org.json.JSONArray
import org.json.JSONObject

/** Builds the Leaflet page served from the offline interceptor. Place text reaches the DOM via textContent only. */
object LeafletPageBuilder {
    const val PAGE_HOST = "cardmap.adt.local"
    const val PAGE_URL = "https://$PAGE_HOST/index.html"
    const val ASSET_ROOT = "cards/leaflet"
    const val TILE_URL_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

    fun build(places: List<ResolvedPlace>, route: Boolean): String {
        val located = places.filter { it.point != null }
        val data = JSONArray().also { arr ->
            located.forEach { p ->
                arr.put(JSONObject().put("n", p.index + 1).put("name", p.name).put("note", p.note ?: "")
                    .put("lat", p.point!!.lat).put("lng", p.point.lng))
            }
        }
        val json = data.toString().replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="/leaflet/leaflet.css">
<style>html,body{height:100%;margin:0}#map{position:absolute;top:0;left:0;right:0;bottom:0}</style></head><body><div id="map"></div>
<script src="/leaflet/leaflet.js"></script>
<script>
var P=$json, ROUTE=$route;
window.onerror=function(msg,src,line){document.title='adt-map-error:'+msg+' ('+line+')';return false;};
var map=null,started=false;
function start(){
  if(started)return;
  var el=document.getElementById('map');
  if(!el||el.clientWidth===0||el.clientHeight===0){requestAnimationFrame(start);return;}
  started=true;
  L.Icon.Default.prototype.options.imagePath='/leaflet/images/';
  map=L.map('map');
  var tiles=L.tileLayer('$TILE_URL_TEMPLATE',{maxZoom:19,attribution:'&copy; OpenStreetMap contributors'}).addTo(map);
  tiles.on('load',function(){document.title='adt-map-ready';});
  var pts=[];
  P.forEach(function(p){
    var ll=[p.lat,p.lng]; pts.push(ll);
    var box=document.createElement('div');
    var b=document.createElement('b'); b.textContent=p.n+'. '+p.name; box.appendChild(b);
    if(p.note){var d=document.createElement('div'); d.textContent=p.note; box.appendChild(d);}
    L.marker(ll).addTo(map).bindPopup(box);
  });
  if(ROUTE&&pts.length>1){L.polyline(pts,{color:'#6650a4',weight:4,opacity:0.85}).addTo(map);}
  function fit(){
    if(pts.length>1){map.fitBounds(L.latLngBounds(pts),{padding:[32,32],maxZoom:16});}
    else if(pts.length==1){map.setView(pts[0],15);}
    else{map.setView([20,0],2);}
  }
  fit();
  document.title='adt-map-ready';
  window.addEventListener('resize',function(){map.invalidateSize();fit();});
}
if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',start);}else{start();}
window.addEventListener('load',start);
</script></body></html>"""
    }
}
