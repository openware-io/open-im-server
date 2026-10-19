> Status: historical
> Archived: 2026-10-19
> Superseded-by:
> Original-location: docs/audit-2026-09-17-exceptions-and-i18n.md

> 娉細鏈枃妗ｅ師鏈?5 澶勭鐞嗗憳鍒濆鍙ｄ护鏄庢枃锛堝惈鍙墽琛?curl 璇锋眰浣擄級宸蹭簬 2026-10-13 鑴辨晱锛屽搴斿嚟鎹凡杞崲銆?>
> **鍘嗗彶鏂囨。**锛氭湰鏂囨槸 2026-09-17 鐨勪竴娆℃€у彧璇诲璁″揩鐓э紝**涓嶆槸鐜拌瑙勮寖鎴栨寚寮?*锛屽叾鐜銆佸湴鍧€銆佺鍙ｃ€佸垎鏀笌缁撹鍧?*鍙褰撴椂鏈夋晥**銆?> 鏂囦腑鍑虹幇鐨勫唴缃戝湴鍧€涓庣鍙ｆ槸**褰撴棩瀹炴祴鐜鐨勮褰?*锛岀敤浜庡鐜板綋鏃剁殑鍙栬瘉杩囩▼锛涘畠浠?*涓嶆槸褰撳墠鍏ュ彛**鈥斺€旂幇琛屽叆鍙ｄ互 [鏈湴閮ㄧ讲璇存槑](../../../deployment/LOCAL.md) 涓哄噯锛堢粺涓€ Ingress锛屾棫鍦板潃涓庢棫绔彛宸插簾寮冿級銆?> **淇濈暀杩欎簺瀛楅潰閲忔槸鍒绘剰鐨?*锛氭敼鍔ㄥ璁″揩鐓х殑瀹炴祴璁板綍绛変簬绡℃敼璇佹嵁銆傛枃妗ｅ垎灞備笌鍘嗗彶鏍囨敞鍙ｅ緞瑙?[宸ョ▼瑙勫垯](../../../ENGINEERING_RULES.md) 涓?[鏁存敼鏂规](../../../renovation/ENGINEERING_GOVERNANCE_03_REMEDIATION.md) 搂17.5銆?
# 璺ㄦ湇鍔″彧璇诲璁℃姤鍛婏細寮傚父涓庨敊璇鐞嗚鑼冩€?+ 鍥介檯鍖?鏂囨涓€鑷存€?
- **瀹¤瀵硅薄**锛歚open-im-server`锛堝垎鏀?`develop/2.0.0-saas-20260826`锛孒EAD `9cbec066`锛夈€乣open-saas-admin`銆乣open-saas-mobile`
- **鎬ц川**锛氬彧璇汇€傛湭淇敼/鍒涘缓/鍒犻櫎浠讳綍涓氬姟浠ｇ爜锛屾湭鎻愪氦锛屾湭鏀圭増鏈彿锛屾湭瑙︾ `k8s/`锛屾湭璺戝彂鐗堣剼鏈€?- **瀹炴祴鐜**锛氭湰鍦?kind 闆嗙兢 `kind-gv-im-local`锛堢綉鍏?`192.168.31.91:30002`锛屽悗鍙?`:30081`锛孊 绔?`:30082`锛沗kubectl` 鐩磋繛 18 涓湇鍔?Pod + `saas-admin` Pod 鍐?`curl`锛?- **瀹¤鏃堕棿**锛?026-09-17
- **璇存槑**锛氭湰鏈轰负 PowerShell 5.1锛坧wsh锛夛紝鏃?heredoc锛沜url 鎺㈤拡璧?`$env:TEMP\cbn_audit\` 涓存椂鐩綍銆?
---

## 鈿狅笍 0. 骞跺彂鍙樻洿涓庡嫎璇紙鍔″繀鍏堣锛?
瀹¤鏈熼棿 `open-saas-admin` 涓?`open-im-server/platform-services/order` **姝ｈ鍏跺畠 agent 瀹炴椂鏀瑰啓**锛岄儴鍒嗚鍙蜂笌缁撹鍦ㄥ璁¤繃绋嬩腑宸茬粡鍙戠敓鍙樺寲銆備互涓嬩负**鏍搁獙鍚庣殑褰撳墠鐘舵€?*锛屾湰鎶ュ憡姝ｆ枃涓彈褰卞搷澶勫凡灏卞湴鏍囨敞銆?
### 0.1 宸茶骞跺彂淇锛堜笉瑕佹寜鏃х粨璁鸿鍔級

| 鏇捐瑙傚療鍒扮殑闂 | 浣嶇疆 | 褰撳墠鐘舵€侊紙宸叉牳楠岋級 |
|---|---|---|
| `orders.vue` 閲嶅澹版槑 `formatTime` 鈫?ESM `SyntaxError`锛宍npm run build` 澶辫触 | `open-saas-admin/src/views/tenant/orders.vue` | 鉁?**宸蹭慨澶?*銆傚綋鍓嶆棤浠讳綍閲嶅椤跺眰鍑芥暟澹版槑锛堝叏 views 鎵弿 0 鍛戒腑锛夛紱`formatTime` 鐜颁粠 `@/utils/format` 瀵煎叆锛坄:452`锛夊苟琚寘瑁呭嚱鏁拌皟鐢紙`:720`锛?|
| `members.vue` 閲戦鐢?`' 鍏?` 鍚庣紑 | `views/tenant/members.vue:150` | 鉁?**宸蹭慨澶?*锛屽叏浠撳凡鏃?`+ ' 鍏?` |
| `order` 鏈嶅姟缂哄皯蹇呭～璇锋眰澶村鐞嗭紙鏈姤鍛?H-6 娑夊強 order 鐨勯儴鍒嗭級 | `platform-services/order/.../handler/GlobalExceptionHandler.java` | 鉁?**宸叉柊澧?* `MissingRequestHeaderException`锛坄:70-77` 鈫?400 `REQUEST_HEADER_MISSING`锛変笌 `ServletRequestBindingException`锛坄:80-87` 鈫?400 `REQUEST_BINDING_INVALID`锛?|

### 0.2 浠嶇劧瀛樺湪锛堝凡鎸夊綋鍓嶆枃浠剁姸鎬侀噸鏂版牳楠岋級

| 闂 | 浣嶇疆锛堝綋鍓嶈鍙凤級 | 璇佹嵁 |
|---|---|---|
| **鍙岃揣甯佺鍙?`楼 楼 123.45`** | `open-saas-admin/src/views/tenant/orders.vue:410` | `<span class="collect-amount">楼 {{ fmtCents(payableMinor) }}</span>`锛岃€?`:818` `const fmtCents = formatYuan`锛宍utils/format.js:21` 杩斿洖 `'楼 ' + ...`銆?*鍏ㄤ粨鍞竴涓€澶?*锛坄:380` 涓?`products.vue:38` 鏄唴鑱?`/100`锛屼笉甯﹀墠缂€锛屾甯革級銆傚揩鐓?md5 `9B2614AAA014EBDB5F14AE8BD9AFC373` |
| order 鍏滃簳 500 浠嶆棤 `code` 涓旇嫳鏂?| `.../order/.../GlobalExceptionHandler.java:44` | `Map.of("message", "Internal error")`锛?*鏈慨** |
| order 鐨?`Exception.class` 浠嶅悶鎺?`NoResourceFoundException` / `HttpRequestMethodNotSupportedException` | `.../order/.../GlobalExceptionHandler.java:41` | 褰撳墠鏂囦欢浠嶅彧鏈?`HttpMessageNotReadableException` / `MissingServletRequestParameterException` / `MissingRequestHeaderException` / `ServletRequestBindingException` 鍥涗釜鏄惧紡鍒嗘敮锛?*鏈鐩?404/405**锛涘疄娴?500 渚濈劧瀛樺湪 |

### 0.3 琛屽彿鍙俊搴?
| 鑼冨洿 | 鍙俊搴?|
|---|---|
| `open-im-server`锛堥櫎 `platform-services/order`锛?| 楂樸€俙git status` 浠?`k8s/*` 涓?`platform-services/order/**` 鏈夋敼鍔?|
| `open-im-server/platform-services/order` | **涓?*銆?5+ 涓枃浠舵湭鎻愪氦鏀瑰姩涓紝鍚?`GlobalExceptionHandler.java`锛涘紩鐢ㄥ墠璇烽噸鏂?`grep` |
| `open-saas-admin` | **浣?*銆?6 涓枃浠舵湭鎻愪氦鏀瑰姩涓紙鍚?4 涓柊澧炲伐鍏锋枃浠讹級锛岃鍙峰湪瀹¤鏈熼棿鎸佺画婕傜Щ锛堝悓涓€鏂囦欢 10 鍒嗛挓鍐呬粠 1661 琛?鈫?1701 鈫?1728 鈫?1725 琛岋級锛?*寮曠敤鍓嶅繀椤婚噸鏂?`grep`** |
| `open-saas-mobile` | **楂?*銆俙git status` 涓虹┖锛孒EAD `737928e`锛屽璁℃湡闂存棤鍙樺寲 |

```powershell
# 寮曠敤浠讳綍鍓嶇琛屽彿鍓嶅厛璺戣繖涓?git -C D:\projects\cnb\open-saas-admin status --porcelain
git -C D:\projects\cnb\open-im-server status --porcelain
```

> **瀹¤鑷韩鐨勫彧璇讳繚璇?*锛氭湰瀹¤鏈啓鍏ヤ笂杩颁换浣曚粨搴撱€備笂琛ㄦ墍鍒楁敼鍔ㄥ叏閮ㄧ敱骞跺彂 agent 浜х敓锛坄git status` 鐨?` M` / `??` 鏍囪锛夛紝鏈姤鍛婂瀹冧滑鐨勬弿杩颁粎鐢ㄤ簬鍖哄垎銆屽凡淇?/ 鏈慨銆嶃€?
---

## 1. 缁撹鎽樿

### 鍚庣锛? 鏉℃渶閲嶈锛?
1. **銆岀粺涓€寮傚父鍑哄彛銆嶅苟鏈叏閲忚惤鍦帮紝婕忕綉鏈嶅姟姣旈鏈熷銆?* 20 涓彲閮ㄧ讲鏈嶅姟涓紝鍙湁 15 涓湁 `@RestControllerAdvice`锛沗common-audit` / `common-mail` / `common-sms` **瀹屽叏娌℃湁浠讳綍 advice**锛堝悇鑷兘鏈?REST controller锛夛紝`group-idaas` 鏈?advice 鍗?*杩?404 閮借繑鍥?Tomcat 鍘熺敓 HTML 閿欒椤?*锛坄Content-Type: text/html;charset=utf-8`锛夈€傚彟鏈?6 涓湇鍔★紙`platform-admin/customer/identity/marketing/payment/payment-channel`锛夊彧鏄犲皠 `ApiException` 鑰?*娌℃湁 `Exception` 鍏滃簳**锛屼换浣曢潪涓氬姟寮傚父鐩存帴钀藉埌 Spring 榛樿閿欒浣?`{"timestamp","status","error","path"}`銆?*娌℃湁浠讳綍 CI 闂ㄧ瑕嗙洊杩欎竴鐐?*锛坄scripts/validate/` 16 涓剼鏈潎涓嶆鏌ワ級銆?
2. **鏈€涓ラ噸鐨勯€€鍖栦笉鏄€?03鈫?00銆嶏紝鑰屾槸銆屽鎴风閿欒鈫?00銆?銆屽唴閮ㄧ粏鑺傚娉勩€嶃€?* 8 涓湇鍔″湪 advice 閲屽啓浜?`@ExceptionHandler(Exception.class)`锛屽畠鎶?Spring MVC 鑷繁鐢ㄤ簬琛ㄨ揪 4xx 鐨勫紓甯革紙`NoResourceFoundException`銆乣HttpRequestMethodNotSupportedException`銆乣HttpMediaTypeNotSupportedException`銆乣MissingServletRequestHeaderException`锛?*鍏ㄩ儴鍚炴垚 500**銆傚疄娴嬶細`PATCH /`銆乣GET /zzz-nonexistent`銆侀敊璇?Content-Type 鍦?`platform-order` / `platform-resource` / `platform-tenant` **涓€寰嬭繑鍥?500**锛沗platform-identity` 鐨?*鐧诲綍鍑嵁閿欒杩斿洖 500**锛坄IllegalStateException` 鏈槧灏勶級銆?   鍏朵腑 4 涓?IM 鏈嶅姟锛坄im-admin` / `im-conversation` / `im-message` / `im-user`锛夌殑鍏滃簳鍒嗘敮鎶?`ex.getMessage()` **鍘熸牱鍐欒繘 500 鍝嶅簲浣?*锛屽疄娴嬫硠闇?Jackson 瑙ｆ瀽鍣ㄥ唴閮ㄤ俊鎭€丣ava 鏂规硶绛惧悕涓庡唴閮ㄨ祫婧愯矾寰勶細
   `{"message":"Required request parameter 'platform' for method parameter type String is not present"}`銆?
3. **闈欓粯 500锛氱敤鎴锋嬁鍒?500锛岃繍缁存嬁涓嶅埌浠讳綍鏃ュ織銆?* `common-media` 涓?4 涓?IM 鏈嶅姟鐨勫厹搴曞垎鏀?*娌℃湁浠讳綍鏃ュ織璇彞**銆傚疄娴嬩汉涓鸿Е鍙戠殑 5 涓?500锛宍kubectl logs --since=6m` **涓€鏉¤褰曢兘娌℃湁**锛堣€屽悓涓€ Pod 鐨勫叾瀹?INFO/WARN 姝ｅ父杈撳嚭锛岃瘉鏄庢棩蹇楃閬撳仴搴凤級銆俙platform-admin/customer/identity/marketing/payment/payment-channel` 杩炲厹搴曞垎鏀兘娌℃湁锛屽悓鏍锋棤涓氬姟涓婁笅鏂囨棩蹇椼€?
4. **蹇呭～璇锋眰澶寸己澶辩粫杩囦簡宸茬粡鍐欏ソ鐨勭粺涓€閿欒鐮侊紙鍙簿纭瘉鏄庯級銆?* `customer` / `marketing` / `payment` 鐨勫簲鐢ㄥ眰**宸茬粡**鏈?`IDEMPOTENCY_KEY_REQUIRED`锛?00 + 涓枃锛夛紝浣?Controller 涓婂啓鐨勬槸 `@RequestHeader("Idempotency-Key")`锛坄required` 榛樿 true锛夛紝**澶寸己澶辨椂鏍规湰璧颁笉鍒板簲鐢ㄥ眰**锛?   - 澶?*缂哄け** 鈫?`400 {"timestamp":...,"status":400,"error":"Bad Request","path":...}`锛?*闈炵粺涓€浣?*锛?   - 澶?*瀛樺湪浣嗕负绌?* 鈫?`400 {"code":"IDEMPOTENCY_KEY_REQUIRED","message":"缂哄皯 Idempotency-Key"}`锛?*缁熶竴浣?*锛?
5. **5xx 鍏滃簳娑堟伅鏈韩涔熶笉缁熶竴銆?* `platform-order` 涓?`common-media` 鐨勫厹搴曡繑鍥炶嫳鏂?`{"message":"Internal error"}` 涓?*娌℃湁 `code` 瀛楁**锛涘彧鏈?`platform-resource` 涓?`platform-tenant` 鏄纭舰鎬侊紙`{"code":"INTERNAL_ERROR","message":"鏈嶅姟鍐呴儴閿欒锛岃绋嶅悗閲嶈瘯"}` + 璁板爢鏍堬級銆俙group-idaas` / `common-audit` / `common-mail` / `common-sms` 鐨勯敊璇綋鏄?Spring 榛樿浣擄紝`gateway` 鏄?WebFlux 榛樿浣擄紝`im-access-ws` 鏃?REST 闈€?*7 绉嶄笉鍚岀殑閿欒浣撳舰鎬佸苟瀛樸€?*

### 鍓嶇锛? 鏉℃渶閲嶈锛?
1. **涓や釜鍓嶇閮芥病鏈?i18n 鍩虹璁炬柦**銆俙open-saas-admin` 鐨?`package.json` 渚濊禆鍙湁 `element-plus / axios / pinia / vue / vue-router`锛屾棤 `vue-i18n`锛涗袱涓粨搴撳潎鏃?`locales/`銆乣zh-CN.json`銆乣en.json` 涔嬬被鏂囦欢銆?*浠诲姟瑕佹眰鐨勩€宨18n key 瑕嗙洊鏍稿銆嶅湪褰撳墠浠ｇ爜涓嬩笉閫傜敤锛圢/A锛?*锛氭病鏈?key锛屼篃灏辨病鏈夌己 key/澶氫綑 key锛屽彧鏈夊叏閲忕‖缂栫爜涓枃銆?
2. **鍚庡彴鍓嶇鐨勯敊璇彁绀哄伐鍏峰凡缁忋€岀煡閬撱€嶅悗绔湪杩斿洖闈炵粺涓€浣擄紝骞跺洜姝や富鍔ㄤ涪寮冨悗绔殑鑻辨枃娑堟伅** 鈥斺€?杩欐棦鏄瘉鎹篃鏄奖鍝嶃€俙open-saas-admin/src/utils/adminErrorMessage.js:7-11` 鐨勬敞閲婄洿鎺ュ啓鏄庯細*銆岀嚎涓婂瓨鍦ㄤ袱绫讳笉甯?`code/message` 鐨勫搷搴?鈥?濡傜己灏?Idempotency-Key / 璇锋眰澶寸己澶辨椂 `{timestamp,status,error,path}`銆?锛涚 64-65 琛岃繕鏈?`CJK_PATTERN` 鐧藉悕鍗曪紝**鍙睍绀哄惈涓棩闊╁瓧绗︾殑鏈嶅姟绔?message**銆?   鍚庢灉瀹炴祴鍙緱锛歚ADMIN_LOGIN_INVALID` 鐨勬秷鎭槸鑻辨枃 `invalid username or password`锛坄AdminLoginApplicationService.java:37`锛夛紝涓旀湭鍒楀叆 `ERROR_CODE_MESSAGES` 鈫?鍚庡彴**鎶娿€岀敤鎴峰悕鎴栧瘑鐮侀敊璇€嶆樉绀烘垚銆岀櫥褰曠姸鎬佸凡澶辨晥锛岃閲嶆柊鐧诲綍銆傘€?*锛堢姸鎬佺爜 401 鍏滃簳锛夈€傚叏閲忔瘮瀵癸細鍚庣 141 涓笟鍔＄爜涓?**120 涓?* 鏃犲墠绔槧灏勶紝鍏朵腑 **18 涓殑娑堟伅鏄嫳鏂?*锛屼細鍏ㄩ儴閫€鍖栨垚鐘舵€佺爜娉涘寲鏂囨銆?
3. **鍌ㄥ€煎搧鐗屽悕鏈?4 绉嶅啓娉曪紝涓旇繚鍙嶄簡浠ｇ爜閲岃嚜宸卞啓涓嬬殑銆屼笉寰楃‖缂栫爜銆嶈鍒欍€?* `open-saas-admin/src/constants/payment-methods.js:14-15` 鏄庣‘娉ㄩ噴 *銆屽偍鍊兼槸鍝佺墝灞曠ず鍚嶏紝鍙栫鎴烽厤缃?`tnt_tenant_config.wallet_brand_name`锛堥粯璁?A380甯侊級锛?*涓嶅緱纭紪鐮?*銆?锛屼絾 `views/tenant/wallet.vue:114,123,140` 涓夊纭紪鐮?`'A380甯?`锛沗payments.vue:108` 涓?`orders.vue:535` 鍙堟妸榛樿鍊煎啓鎴?`'鍌ㄥ€?`锛沗payment-methods.js:4,11,22` 鏄?`'鍌ㄥ€煎竵(A380甯?'` / `'鍌ㄥ€煎竵'` / `'鍌ㄥ€?`銆傚悓涓€姒傚康 **鍌ㄥ€?/ 鍌ㄥ€煎竵 / 鍌ㄥ€煎竵(A380甯? / A380甯?* 鍥涘悕骞跺瓨銆?
4. **鏈涓庨噾棰?鏃堕棿鏍煎紡閮藉湪銆屽悇鍐欏悇鐨勩€嶃€?* 鑿滃崟鏍囬鐩存帴鍐欐垚 `'鍖呭帰/璧勬簮'`锛坄open-saas-admin/src/router/index.js:71`锛夆€斺€?鏈灏氭湭瀹氱锛涖€屽簲鏀躲€?5 娆?vs銆屽緟鏀躲€? 娆★紙`open-saas-mobile/src/b-end/views/Orders.vue:58`锛夋寚鍚屼竴閲戦锛沗楼` 鍓嶇紑涓?`鍏僠 鍚庣紑骞跺瓨锛坄members.vue:150` 鏄?`... + ' 鍏?`锛夛紝鍚屼竴浠撳簱鑷冲皯 **7 澶勫悇鑷疄鐜?*浜嗗厓/鍒嗚浆鎹紙`views/tenant/orders.vue:818,824`銆乣payments.vue:138`銆乣shift.vue:174`銆乣reservations.vue:75`銆乣members.vue:150`銆乣ktv-config.vue:270`锛夛紝鑰岃鑼冨疄鐜?`src/utils/format.js:16-21` 鍙嶈€屾病浜虹敤锛涖€屽垎銆嶅悓鏃惰〃绀?*璐у竵鏈€灏忓崟浣?*鍜?*鍒嗛挓**锛坄ktv-config.vue:65,223` 鐨?`閫掑绮掑害(鍒?` 鏄垎閽燂紝鍚屾枃浠?`:270 fenToYuan` 鏄垎甯侊級銆傛棩鏈熷垯缁濆ぇ澶氭暟**鐩存帴娓叉煋鍘熷鍊?*锛坄platform/tenants.vue:19`銆乣shift.vue:64`銆乣ktv-config.vue:164`锛夈€?
5. **闄ら鏍奸棶棰樺锛屽彟鏈?6 涓‘瀹氭€х己闄凤紙鍙洿鎺ヤ慨锛岄潪椋庢牸鍋忓ソ锛夈€?* 鈶?`open-saas-admin` 鏀堕摱鍙版覆鏌?**"楼 楼 123.45"**锛坄views/tenant/orders.vue:410` 瀛楅潰 `楼` + `fmtCents` 宸插惈 `'楼 '` 鍓嶇紑锛夛紱鈶?C 绔悓涓€ DTO 鍚屼竴瀛楁鐢ㄤ袱绉嶉噾棰濆昂搴︼紝`c-end/app.js:853` 鐢ㄤ笉闄?100 鐨?`money()`锛?*鏄剧ず涓哄疄闄呭€肩殑 100 鍊?*锛涒憿 C 绔?*閽卞寘浣欓鏈櫎 100**锛屾樉绀虹殑鏄€屽垎銆嶇殑鏁板€硷紙`c-end/app.js:200,204,222,227,248,756,760`锛岃€?`c-end/saas.js:107` 娉ㄩ噴涓?`fenToYuan` 鏄庣‘濂戠害鏄€屽垎鈫掑厓銆嶏紱mock 鏁版嵁 `data.js:16 balance: 8880` 鎭板ソ鏄厓褰㈢姸锛?*鎺╃洊浜嗚繖涓?bug**锛夛紱鈶?C 绔?`dateOffset` 鐢?`toISOString().slice(0,10)`锛?*UTC**锛夛紝UTC+8 涓嬫湰鍦?00:00鈥?7:59 鐨勩€屼粖澶?N銆?*鏃╀竴澶?*锛坄c-end/app.js:33,655`锛夛紱鈶?`c-end/app.js:314-316` 鏈湴瑙ｆ瀽銆乁TC 搴忓垪鍖栵紝鎻愪氦鐨勯绾︽椂闂?*鏃?8 灏忔椂**锛堥渶鍚庣濂戠害纭锛夛紱鈶?`formatTime` 鍦?admin 鏈?9 澶勬湰鍦伴噸澶嶅疄鐜帮紝鑰?`utils/format.js` 鐨?`formatYuanValue` / `resourceStateText` **0 璋冪敤鐐?*銆?   > 娉細瀹¤杩囩▼涓浘瑙傚療鍒?`orders.vue` **閲嶅澹版槑 `formatTime` 瀵艰嚧鏋勫缓澶辫触**銆佷互鍙?`members.vue` 鐨?`' 鍏?` 鍚庣紑 鈥斺€?杩欎袱椤瑰凡琚苟鍙戞敼鍔ㄧ殑鍙︿竴涓?agent 淇锛岃 搂0.1銆?
---

## 2. 寮傚父澶勭悊闂娓呭崟锛堟湇鍔?鈫?鏂囦欢:琛?鈫?鐜拌薄 鈫?褰卞搷 鈫?寤鸿 鈫?涓ラ噸搴︼級

### 2.1 楂橈紙High锛?
#### H-1 璺敱 / 鏂规硶 / 濯掍綋绫诲瀷涓嶅尮閰嶄竴寰嬮€€鍖栨垚 500 鈥斺€?8 涓湇鍔?
| 鏈嶅姟 | 缁熶竴鍑哄彛浣嶇疆 |
|---|---|
| platform-order | `platform-services/order/platform-order-service/src/main/java/io/openware/platform/order/handler/GlobalExceptionHandler.java:41-45` |
| platform-resource | `platform-services/resource/platform-resource-service/src/main/java/io/openware/platform/resource/handler/GlobalExceptionHandler.java:84-91` |
| platform-tenant | `platform-services/tenant/platform-tenant-service/src/main/java/io/openware/platform/tenant/handler/GlobalExceptionHandler.java:85-92` |
| common-media | `common-services/media/common-media-service/src/main/java/io/openware/common/media/handler/GlobalExceptionHandler.java:38-43` |
| im-admin | `im-services/admin/im-admin-service/src/main/java/io/openware/im/admin/handler/GlobalExceptionHandler.java:62-67` |
| im-conversation | `im-services/conversation/im-conversation-service/src/main/java/io/openware/im/conversation/handler/GlobalExceptionHandler.java:53-58` |
| im-message | `im-services/message/im-message-service/src/main/java/io/openware/im/message/handler/GlobalExceptionHandler.java:52-57` |
| im-user | `im-services/user/im-user-service/src/main/java/io/openware/im/user/handler/GlobalExceptionHandler.java:69-74` |

- **鐜拌薄**锛歚@ExceptionHandler(Exception.class)` 鐨勫尮閰嶄紭鍏堢骇楂樹簬 `DefaultHandlerExceptionResolver`锛屾妸 Spring 鐢ㄤ簬琛ㄨ揪 4xx 鐨勬鏋跺紓甯稿叏閮ㄦ埅鑾枫€侾od 鏃ュ織鍘熸枃锛坄platform-resource-service`锛夛細
  `org.springframework.web.HttpRequestMethodNotSupportedException: Request method 'GET' is not supported`
  `org.springframework.web.servlet.resource.NoResourceFoundException: No static resource admin/zzz-nonexistent for request '/admin/zzz-nonexistent'.`
- **褰卞搷**锛?04/405/415 鍏ㄩ儴鍙樻垚 500銆傚墠绔妸瀹冨綊鎴愩€屽悗绔湇鍔″紓甯搞€嶅苟鍙兘瑙﹀彂閲嶈瘯锛涚洃鎺т笂 4xx 涓?5xx 瀹屽叏娣锋穯锛?00 鍛婅澶卞幓鎰忎箟锛堟湰娆″璁′腑 5 娆°€?00銆嶅叏鏄鎴风閿欒锛夈€?- **寤鸿**锛氬厹搴曟敼涓?`@ExceptionHandler(Throwable.class)` 涔嬪墠鍏堟樉寮忓０鏄庢鏋跺紓甯革紝鎴栨渶绠€鍋氭硶 鈥斺€?璁?advice 缁ф壙/濮旀墭 `ResponseEntityExceptionHandler`锛屼粎瑕嗙洊 `Exception.class` 涔嬪鐨勬湭鐭ュ紓甯革紱瀵瑰凡澹版槑鐨勬鏋跺紓甯哥粺涓€缈昏瘧鎴?`{code,message}` 骞朵繚鐣欐纭姸鎬佺爜锛?04 `ROUTE_NOT_FOUND`銆?05 `METHOD_NOT_ALLOWED`銆?15 `MEDIA_TYPE_UNSUPPORTED`锛夈€?- **涓ラ噸搴?*锛?*楂?*

**瀹炴祴鍝嶅簲鍘熸枃**

```
### platform-order-service (缁忕綉鍏?:30002锛屽凡鐧诲綍+宸查€変笂涓嬫枃)
GET  /api/v1/business/orders/999999999   -> HTTP 500  {"message":"Internal error"}
GET  /api/v1/business/orders/abc         -> HTTP 500  {"message":"Internal error"}
PATCH http://platform-order-service:4130/ -> HTTP 500 {"message":"Internal error"}
GET  http://platform-order-service:4130/zzz-nonexistent -> HTTP 500 {"message":"Internal error"}

### platform-resource-service锛堢洿杩?:4120锛?PATCH http://platform-resource-service:4120/ -> HTTP 500 {"code":"INTERNAL_ERROR","message":"鏈嶅姟鍐呴儴閿欒锛岃绋嶅悗閲嶈瘯"}
GET   http://platform-resource-service:4120/admin/resources/999999999 -> HTTP 500 {"code":"INTERNAL_ERROR","message":"鏈嶅姟鍐呴儴閿欒锛岃绋嶅悗閲嶈瘯"}

### platform-tenant-service锛堢洿杩?:4110锛?GET  http://platform-tenant-service:4110/admin/tenant/contexts -> HTTP 500 {"code":"INTERNAL_ERROR","message":"鏈嶅姟鍐呴儴閿欒锛岃绋嶅悗閲嶈瘯"}
POST http://platform-tenant-service:4110/admin/tenant/stores (Content-Type: application/xml) -> HTTP 500 {"code":"INTERNAL_ERROR","message":"鏈嶅姟鍐呴儴閿欒锛岃绋嶅悗閲嶈瘯"}
```

> **瀵圭収璇佹嵁锛堣鏄庣粺涓€鍑哄彛鏈韩鏄ソ鐨勶紝鍙槸娌℃帴浣忔鏋跺紓甯革級**锛?> `PUT /api/v1/admin/resources/999999999` 鈫?`404 {"code":"RESOURCE_NOT_FOUND","message":"璧勬簮涓嶅瓨鍦ㄦ垨宸茶鍒犻櫎"}`
> `POST /api/v1/business/orders/999999999/confirm` 鈫?`404 {"code":"ORDER_NOT_FOUND","message":"璁㈠崟涓嶅瓨鍦?}`

#### H-2 500 娉勯湶鍐呴儴缁嗚妭锛圝ackson 鍐呴儴淇℃伅 / Java 鏂规硶绛惧悕 / 鍐呴儴璺緞锛夆€斺€?4 涓?IM 鏈嶅姟

- **浣嶇疆**锛歚im-admin/.../GlobalExceptionHandler.java:62-67`銆乣im-conversation/.../GlobalExceptionHandler.java:53-58`銆乣im-message/.../GlobalExceptionHandler.java:52-57`銆乣im-user/.../GlobalExceptionHandler.java:69-74`
- **鐜拌薄**锛歚body.put("message", ex.getMessage() != null ? ex.getMessage() : "Internal error")`
- **褰卞搷**锛氬悜鏈璇?宸茶璇佽皟鐢ㄦ柟娉勯湶渚濊禆鐗堟湰鐗瑰緛涓庡唴閮ㄧ粨鏋勶紝鍙敤浜庢寚绾硅瘑鍒笌鎺㈡祴锛涘悓鏃舵秷鎭槸鑻辨枃锛岀洿鎺ヨ繚鍙嶅澶栦腑鏂囩害瀹氥€?- **寤鸿**锛氬厹搴曡繑鍥炲浐瀹氭枃妗?+ `code`锛坄INTERNAL_ERROR` / `鏈嶅姟鍐呴儴閿欒锛岃绋嶅悗閲嶈瘯`锛夛紝鍘熷娑堟伅鍙繘鏃ュ織锛涙妸涓婅堪妗嗘灦寮傚父鍗曠嫭鏄犲皠涓?400/404/405銆?- **涓ラ噸搴?*锛?*楂?*

**瀹炴祴鍝嶅簲鍘熸枃锛堝潎涓烘湭璁よ瘉鍗冲彲瑙﹁揪鐨勫叕寮€璺敱锛?*

```
POST http://im-admin-service:3400/client/release-check  -d '{bad json'
  -> HTTP 500 {"message":"JSON parse error: Unexpected character ('b' (code 98)): was expecting double-quote to start property name"}

GET  http://im-admin-service:3400/client/releases/latest          锛堢己 platform 鍙傛暟锛?  -> HTTP 500 {"message":"Required request parameter 'platform' for method parameter type String is not present"}

GET  http://im-admin-service:3400/config/client/zzz
  -> HTTP 500 {"message":"No static resource config/client/zzz for request '/config/client/zzz'."}

GET  http://im-user-service:3100/oauth/authorize                  锛堢己 response_type锛?  -> HTTP 500 {"message":"Required request parameter 'response_type' for method parameter type String is not present"}

POST http://im-user-service:3100/oauth/token  -d '{bad'
  -> HTTP 500 {"message":"JSON parse error: Unexpected character ('b' (code 98)): was expecting double-quote to start property name"}

GET  http://im-user-service:3100/open/zzz
  -> HTTP 500 {"message":"No static resource open/zzz for request '/open/zzz'."}

GET  http://im-user-service:3100/oauth/zzz
  -> HTTP 500 {"message":"No static resource oauth/zzz for request '/oauth/zzz'."}
```

> `/oauth/zzz` 杩欐潯灏ゅ叾鍊煎緱娉ㄦ剰锛?*OAuth 绔偣杩斿洖 500 + 闈?OAuth 閿欒浣?*锛岃€屼笉鏄?`{"error":"invalid_request"}`锛屼細璁╂爣鍑?OAuth 瀹㈡埛绔嬁鍒版棤娉曡В閲婄殑鍝嶅簲銆?
#### H-3 闈欓粯 500锛氭湁鍝嶅簲銆佹棤鏃ュ織 鈥斺€?`common-media` + 4 涓?IM 鏈嶅姟

- **浣嶇疆**锛氬悓 H-2 鍥涘 + `common-services/media/common-media-service/src/main/java/io/openware/common/media/handler/GlobalExceptionHandler.java:38-43`
- **鐜拌薄**锛氬厹搴曟柟娉曚綋鍐呮病鏈変换浣?`log.*` 璋冪敤銆?- **褰卞搷**锛氱嚎涓?500 鏃犳硶瀹氫綅锛涙帓闅滃彧鑳介潬澶嶇幇銆傛湰椤逛笌 H-1 鍙犲姞鍚庡嵄闄╁害鏇撮珮锛堝ぇ閲忋€?00銆嶅叾瀹炴槸瀹㈡埛绔敊璇紝鍗磋繛鏃ュ織閮芥病鏈夛紝鍙兘闈犵寽锛夈€?- **寤鸿**锛氬厹搴曞繀椤?`log.error("<service> unhandled exception, uri={}", request.getRequestURI(), ex)`锛屽苟鎶?advise 鍔犱笂 `@Slf4j`銆?- **涓ラ噸搴?*锛?*楂?*

**瀹炴祴璇佹嵁锛堟棩蹇楃閬撳仴搴凤紝鍞嫭娌℃湁杩欎簺 500 鐨勮褰曪級**

```
$ kubectl -n gv-im-local logs im-admin-service-77596c844b-2b942 --since=6m
锛堢┖锛?$ kubectl -n gv-im-local logs im-user-service-6f4cf5b588-b4ppk --since=6m
锛堢┖锛?$ kubectl -n gv-im-local logs common-media-service-67854d859c-p2jrn --tail=12
2026-09-17T01:44:18.000Z  INFO ... c.g.c.m.media.MediaImageUploadService : Public image stored: ...
2026-09-17T02:35:15.856Z  WARN ... .i.s.InternalServiceAuthenticationFilter : Rejected internal service request: ...
锛堝悓 Pod 鍏跺畠 INFO/WARN 姝ｅ父锛岃瘉鏄庢棩蹇楀彲鐢紱浜轰负瑙﹀彂 500 鐨勬椂闂寸偣 02:34-02:36 鏃犱换浣曢敊璇棩蹇楋級
```

#### H-4 鐧诲綍澶辫触杩斿洖 500锛堝簲 401锛夆€斺€?platform-identity-service

- **浣嶇疆**锛歚platform-services/identity/platform-identity-service/src/main/java/io/openware/platform/identity/application/AccountApplicationService.java:153-155`
  ```java
  if (li == null || !passwordEncoder.matches(credential, li.getCredential())) {
      throw new IllegalStateException("AUTH_INVALID_CREDENTIAL");
  }
  ```
  涓?`platform-services/identity/.../handler/GlobalExceptionHandler.java:17-33`锛堝彧鏄犲皠 `ApiException` 涓?`IllegalArgumentException`锛?*娌℃湁 `IllegalStateException`锛屼篃娌℃湁 `Exception` 鍏滃簳**锛?- **鐜拌薄**锛歚IllegalStateException` 钀藉埌 Spring 榛樿閿欒澶勭悊銆?- **褰卞搷**锛氱櫥褰曞け璐ュ墠绔殑銆岃处鍙峰瘑鐮侀敊璇€嶅垎鏀案杩滄嬁涓嶅埌 401锛涢敊璇粺璁￠噷鐧诲綍澶辫触鍏ㄩ儴璁″叆 5xx锛屾帺鐩栫湡瀹炴晠闅滅巼锛涚敤缁欏畾鍑嵁 `admin/芦宸茶疆鎹?绠＄悊鍛樺垵濮嬪彛浠ぢ籤 鐩存帴璋冪敤璇ユ帴鍙ｄ篃鏄?500銆?- **寤鸿**锛氭敼鎶?`ApiException(401, "AUTH_INVALID_CREDENTIAL", "鐢ㄦ埛鍚嶆垨瀵嗙爜閿欒")`锛涘悓鏃剁粰璇ユ湇鍔¤ˉ `Exception` + `HttpMessageNotReadableException` 鍏滃簳銆?- **涓ラ噸搴?*锛?*楂?*

**瀹炴祴鍝嶅簲鍘熸枃**

```
POST http://192.168.31.91:30081/api/v1/identity/login  {"loginType":"username","loginIdentifier":"admin","credential":"芦宸茶疆鎹?绠＄悊鍛樺垵濮嬪彛浠ぢ?}
  -> HTTP 500 {"timestamp":"2026-09-17T02:33:27.125Z","status":500,"error":"Internal Server Error","path":"/identity/login"}
POST 鍚屼笂锛堜笉瀛樺湪鐨勭敤鎴?+ 閿欒瀵嗙爜锛?  -> HTTP 500 {"timestamp":"2026-09-17T02:33:33.500Z","status":500,"error":"Internal Server Error","path":"/identity/login"}
POST 鍚屼笂锛堢┖浣?{}锛?  -> HTTP 500 {"timestamp":"2026-09-17T02:33:33.599Z","status":500,"error":"Internal Server Error","path":"/identity/login"}
POST 鍚屼笂锛堢暩褰?JSON '{not json'锛?  -> HTTP 400 {"timestamp":"2026-09-17T02:33:33.563Z","status":400,"error":"Bad Request","path":"/identity/login"}
```

#### H-5 6 涓湇鍔℃病鏈?500 鍏滃簳锛? 涓湇鍔″畬鍏ㄦ病鏈夌粺涓€鍑哄彛

| 鏈嶅姟 | 缁熶竴鍑哄彛 | 缂哄け椤?|
|---|---|---|
| platform-admin | `platform-services/admin/platform-admin-service/src/main/java/io/openware/platform/admin/handler/GlobalExceptionHandler.java:15-23` | 鍙湁 `ApiException`锛涙棤 `Exception` 鍏滃簳銆佹棤 `@Slf4j` |
| platform-customer | `.../customer/.../handler/GlobalExceptionHandler.java:17-25` | 鍚屼笂 |
| platform-identity | `.../identity/.../handler/GlobalExceptionHandler.java:17-33` | 鏃?`Exception` 鍏滃簳锛沗handleIllegalArgument:27-33` 鐩存帴鍥炴樉 `ex.getMessage()` |
| platform-marketing | `.../marketing/.../handler/GlobalExceptionHandler.java:17-25` | 鍙湁 `ApiException` |
| common-payment | `common-services/payment/.../handler/GlobalExceptionHandler.java:18-26` | 鍙湁 `ApiException` |
| common-payment-channel | `common-services/payment-channel/.../handler/GlobalExceptionHandler.java:15-23` | 鍙湁 `ApiException` |
| **common-audit** | **鏃?* | 鏈?`AuditController`锛坄common-services/audit/.../api/controller/AuditController.java`锛夛紝鏃?advice |
| **common-mail** | **鏃?* | 鏈?`MailController`锛屾棤 advice |
| **common-sms** | **鏃?* | 鏈?`SmsController`锛屾棤 advice |
| group-idaas | `group-services/idaas/.../handler/GlobalExceptionHandler.java:17-34` | 鍙湁 `ApiException` + 鏂规硶鍙傛暟鏍￠獙锛?*404 杩斿洖 Tomcat HTML** |

- **褰卞搷**锛氶潪涓氬姟寮傚父涓€寰?Spring 榛樿閿欒浣擄紝鍓嶇鍙兘鎸夌姸鎬佺爜鍏滃簳锛堣鎽樿鍓嶇绗?2 鏉★級锛沘udit/mail/sms 鐨?`ClassCastException`锛堜緥濡?`AuditLogApplicationService.java:20-21` 瀵?`tenantId` 鍋?`(Number)` 寮鸿浆锛変細鐩存帴鍙樻垚 500 榛樿椤点€?- **瀹炴祴锛歡roup-idaas 鐨?404 鏄?HTML锛屼笉鏄?JSON**
  ```
  GET http://group-idaas-service:4220/zzz
  HTTP/1.1 404
  Content-Type: text/html;charset=utf-8
  Content-Language: en
  Content-Length: 431
  <!doctype html><html lang="en"><head><title>HTTP Status 404 鈥?Not Found</title>...
  ```
  闈?JSON + `lang="en"`锛屼换浣?JSON 瀹㈡埛绔€佷互鍙婂悗鍙扮殑閿欒瑙ｆ瀽宸ュ叿閮戒細鐩存帴澶辫触銆?- **寤鸿**锛氱粰 audit/mail/sms 浠庨浂琛?advice锛堝彲澶嶅埗 `platform-resource` 鐗堟湰锛屽惈 `Exception` 鍏滃簳 + `@Slf4j`锛夛紱缁?6 涓€屽彧鏈?ApiException銆嶇殑鏈嶅姟琛?`Exception` 鍏滃簳锛涙帓鏌?group-idaas 涓轰綍鏈敞鍐?Boot `BasicErrorController`锛坄application.yml` 鐨?`server:` 娈垫垨 war 鎵撳寘鏂瑰紡锛夈€?- **涓ラ噸搴?*锛?*楂?*

#### H-6 蹇呭～ `@RequestHeader("Idempotency-Key")` 缂哄け缁曡繃缁熶竴閿欒鐮?
- **浣嶇疆锛圕ontroller 澹版槑锛宍required` 榛樿涓?true锛?*锛?  - `platform-services/customer/.../api/controller/WalletAdminController.java:20,27`
  - `platform-services/marketing/.../api/controller/CouponController.java:44`
  - `common-services/payment/.../api/controller/CollectController.java:27`
  - `common-services/media/.../controller/MediaUploadSessionController.java:26,34`
  - `im-services/admin/.../api/controller/ClientReleaseController.java:27-36`锛堜笖绫诲瀷鏄?`UUID`锛?- **宸茬粡鍐欏ソ鍗存墦涓嶅埌鐨勭粺涓€閿欒鐮?*锛歚platform-services/customer/.../application/WalletApplicationService.java:110,196`銆乣platform-services/marketing/.../application/CouponApplicationService.java:79`銆乣common-services/payment/.../application/CollectApplicationService.java:94`锛堝潎涓?`400 IDEMPOTENCY_KEY_REQUIRED / 缂哄皯 Idempotency-Key`锛?- **鐜拌薄**锛氬ご缂哄け鏃?Spring 鎶?`MissingRequestHeaderException`锛屽湪 advice 鏈０鏄庤绫诲瀷鐨勫墠鎻愪笅鐢?`DefaultHandlerExceptionResolver` 澶勭悊 鈫?400 + 榛樿浣擄紱鍦ㄥ０鏄庝簡 `Exception` 鍏滃簳鐨勬湇鍔￠噷锛坢edia / im-admin 绛夛級鍙嶈€屽彉鎴?500銆?- **褰卞搷**锛氬悓涓€涓氬姟閿欒鍥犮€屽ご缂哄け vs 澶翠负绌恒€嶈繑鍥炰袱绉嶅畬鍏ㄤ笉鍚岀殑鍝嶅簲浣擄紱瀹㈡埛绔笌瀹㈡湇鑴氭湰鏃犳硶鎸?`code` 鍒嗘敮銆?- **寤鸿**锛堜簩閫変竴锛屾帹鑽愬墠鑰咃級锛氣憼 Controller 鏀?`@RequestHeader(value="Idempotency-Key", required=false)`锛岃搴旂敤灞傜粺涓€鎶涚爜锛涒憽 鍦?advice 閲岃ˉ `@ExceptionHandler(MissingRequestHeaderException.class)` 鈫?`400 REQUEST_HEADER_MISSING`锛堜腑鏂囷級銆?*娉ㄦ剰 media 宸插疄鐜拌鍒嗘敮锛屼絾鏂囨鏄嫳鏂?*锛堣 M-2锛夈€?- **涓ラ噸搴?*锛?*楂?*

**瀹炴祴鍝嶅簲鍘熸枃锛堝悓涓€鎺ュ彛锛屼粎璇锋眰澶存湁鏃犱箣宸級**

```
POST /api/v1/admin/wallets/recharge   锛堜笉甯?Idempotency-Key锛?  -> HTTP 400 {"timestamp":"2026-09-17T02:36:30.207Z","status":400,"error":"Bad Request","path":"/admin/wallets/recharge"}
POST /api/v1/admin/wallets/recharge   锛堝甫绌哄€?Idempotency-Key锛?  -> HTTP 400 {"code":"IDEMPOTENCY_KEY_REQUIRED","message":"缂哄皯 Idempotency-Key"}
POST /api/v1/business/coupons/999999999/issue  锛堜笉甯?Idempotency-Key锛?  -> HTTP 400 {"timestamp":"2026-09-17T02:35:59.545Z","status":400,"error":"Bad Request","path":"/business/coupons/999999999/issue"}
```

---

### 2.2 涓紙Medium锛?
#### M-1 鍏滃簳 500 缂?`code` 瀛楁涓斾负鑻辨枃 鈥斺€?platform-order / common-media

- `platform-services/order/.../handler/GlobalExceptionHandler.java:44`锛歚return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(Map.of("message", "Internal error"));`
- `common-services/media/.../handler/GlobalExceptionHandler.java:40-42`锛氬悓褰㈡€?- **褰卞搷**锛氫笌 `{code,message}` 濂戠害涓嶇锛涘墠绔彧鑳借蛋鐘舵€佺爜鍏滃簳锛涜繚鍙嶅澶栦腑鏂囩害瀹氥€傚疄娴?`{"message":"Internal error"}`銆?- **寤鸿**锛氱粺涓€涓?`{"code":"INTERNAL_ERROR","message":"鏈嶅姟鍐呴儴閿欒锛岃绋嶅悗閲嶈瘯"}`銆?- **涓ラ噸搴?*锛氫腑

#### M-2 media 鐨勯儴鍒嗛敊璇綋缂?`code` 涓旀枃妗堟槸鑻辨枃

- `common-services/media/.../handler/GlobalExceptionHandler.java:30`锛歚"Validation failed"`锛堜笖鏃?`code`锛?- `:35`锛歚"Required request header is missing: " + exception.getHeaderName()`
- `:46`锛歚badRequest(message)` 鈫?`Map.of("message", message)` 鈥?**鍙湁 message锛屾病鏈?code**
- **褰卞搷**锛歮edia 鐨?400 鍏ㄩ儴娌℃湁鏈哄櫒鍙垽鍒殑 `code`銆?- **寤鸿**锛氳ˉ `INVALID_ARGUMENT` / `REQUEST_HEADER_MISSING`锛屾枃妗堟敼涓枃銆?- **涓ラ噸搴?*锛氫腑

#### M-3 Spring 榛樿閿欒浣撳ぇ闈㈢Н瀛樺湪锛堝彲瀹炴祴鏋氫妇锛?
鐩磋繛姣忔湇鍔＄殑銆屼笉鏀寔鏂规硶 / 鏈煡璺緞 / 閿欒 Content-Type銆嶅洓杩炴帰閽堢粨鏋滐紙`ci` = 鍦?`saas-admin` Pod 鍐?`curl`锛夛細

| 鏈嶅姟 | PATCH `/` | GET `/zzz-nonexistent` | 鍒ゅ畾 |
|---|---|---|---|
| platform-admin | 404 榛樿浣?| 404 榛樿浣?| 闈炵粺涓€浣?|
| platform-customer | 404 榛樿浣?| 404 榛樿浣?| 闈炵粺涓€浣?|
| platform-identity | 404 榛樿浣?| 404 榛樿浣?| 闈炵粺涓€浣?|
| platform-marketing | 404 榛樿浣?| 404 榛樿浣?| 闈炵粺涓€浣?|
| common-audit | 404 榛樿浣?| 404 榛樿浣?| 闈炵粺涓€浣?|
| common-mail | 404 榛樿浣?| 404 榛樿浣?| 闈炵粺涓€浣?|
| common-payment | 404 榛樿浣?| 404 榛樿浣?| 闈炵粺涓€浣?|
| common-payment-channel | 404 榛樿浣?| 404 榛樿浣?| 闈炵粺涓€浣?|
| common-sms | 404 榛樿浣?| 404 榛樿浣?| 闈炵粺涓€浣?|
| group-idaas | 404 **HTML** | 404 **HTML** | 闈?JSON |
| gateway | 404 WebFlux 榛樿浣?| 404 WebFlux 榛樿浣?| 闈炵粺涓€浣?|
| platform-order | **500** | **500** | 瑙?H-1 |
| platform-resource | **500** | **500** | 瑙?H-1 |
| platform-tenant | **500** | **500** | 瑙?H-1 |
| common-media / im-admin / im-conversation / im-message / im-user | 401锛圫pring Security 鍏堟嫤锛?| 401 | 鏈璇佷笉鍙揪锛屼絾宸茶璇佽姹傚懡涓悓涓€鍏滃簳 |

- **涓ラ噸搴?*锛氫腑

#### M-4 缃戝叧鑷韩鐨勯敊璇綋涓嶇粺涓€

- `gateways/gateway/src/main/java/io/openware/gateway/ratelimit/RateLimitFilter.java:32-33`锛歚{"code":429,"message":"璇锋眰杩囦簬棰戠箒锛岃绋嶅悗鍐嶈瘯"}` 鈥斺€?**`code` 鏄暟瀛?429**锛岃€屽叏浠撳簱鍏跺畠 141 涓爜閮芥槸 `UPPER_SNAKE` 瀛楃涓诧紱429 涔熶笉鍦?`sdk/common/.../HttpStatusCodes.java` 甯搁噺琛ㄩ噷锛堣琛ㄦ病鏈?429锛夈€?- `gateways/gateway/.../security/SaasSessionAuthenticationFilter.java:200-207`锛?01 浣?`{"code":"SAAS_SESSION_REQUIRED","message":"SaaS session is required"}` 鈥斺€?鑻辨枃锛涗笖**浼氳瘽瀛樺湪浣嗙鎴蜂笂涓嬫枃涓虹┖鏃舵姤鐨勭爜鏄?`SAAS_CONTEXT_REQUIRED`**锛坄:87`锛夛紝涓庝笅娓哥殑 `TENANT_CONTEXT_REQUIRED` / `TENANT_CONTEXT_MISSING` 涓夎冻榧庣珛锛堣 4.1锛夈€?- 缃戝叧鏈尮閰嶈矾鐢憋細WebFlux 榛樿浣?`{"timestamp":"...","path":"...","status":404,"error":"Not Found","requestId":"..."}`锛堝疄娴嬶級銆?- **涓ラ噸搴?*锛氫腑

#### M-5 鍑洪敊鏃惰繑鍥?HTTP 200 + 閿欒杞借嵎 鈥斺€?platform-order 鎶ヨ〃

- `platform-services/order/platform-order-service/src/main/java/io/openware/platform/order/api/controller/ReportController.java:42`銆乣:68`銆乣:90`
  ```java
  if (tenantId == null) { return Map.of("error", "TENANT_CONTEXT_MISSING"); }
  ```
- **褰卞搷**锛氫笌鍚屼粨搴撳叾瀹冩姤琛紙`platform-admin/ReportController.java:171` 鎶?`ApiException(401, "TENANT_CONTEXT_MISSING", ...)`锛夎涓虹浉鍙嶏紱璋冪敤鏂规寜 HTTP 鐘舵€佸垽鏂垚鍔熶細璇垽涓虹┖鎶ヨ〃銆?- **寤鸿**锛氭敼涓烘姏 `ApiException(401, "TENANT_CONTEXT_MISSING", "缂哄皯绉熸埛涓婁笅鏂?)`銆?- **涓ラ噸搴?*锛氫腑

#### M-6 im-admin 鎶婁笅娓稿搷搴斾綋鍘熸牱閫忎紶

- `im-services/admin/.../handler/GlobalExceptionHandler.java:50-55`
  ```java
  @ExceptionHandler(RestClientResponseException.class)
  ... .body(exception.getResponseBodyAsString());
  ```
- **褰卞搷**锛氫笅娓告湇鍔＄殑鍐呴儴閿欒浣擄紙鍚?H-2 閭ｇ被娉勯湶鏂囨锛変細缁?im-admin 浜屾杞彂缁欏鎴风锛屾斁澶?H-2 鐨勫奖鍝嶉潰锛涗笖鍐呭绫诲瀷/褰㈢姸涓嶅彈鏈湇鍔℃帶鍒躲€?- **寤鸿**锛氬彧閫忎紶 `code`/`message` 鐧藉悕鍗曞瓧娈碉紝鍏朵綑钀芥棩蹇椼€?- **涓ラ噸搴?*锛氫腑

#### M-7 media 鐨?`IllegalArgumentException` 鍙?500

- `common-services/media/.../controller/MediaUploadSessionController.java:50-56`锛歚requireIdempotencyKey` 鎶涜嫳鏂?`IllegalArgumentException("Idempotency-Key must be a UUID")`锛沵edia 鐨?advice 鏈０鏄?`IllegalArgumentException` 鈫?鍛戒腑 `Exception` 鍏滃簳 鈫?`500 {"message":"Internal error"}`
- **褰卞搷**锛氬鎴风浼犱簡闈?UUID 鐨勫箓绛夐敭锛屽緱鍒?500 鑰岄潪 400锛屼笖鍘熷洜涓㈠け銆?- **寤鸿**锛氭敼鎶?`ApiException(400, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 蹇呴』鏄?UUID")`銆?- **涓ラ噸搴?*锛氫腑

#### M-8 IM 鏈嶅姟鐨勫弬鏁版牎楠岄敊璇己 `code`

- `im-admin/.../GlobalExceptionHandler.java:42-48`銆乣im-conversation/.../:40-46`銆乣im-message/.../:40-46`銆乣im-user/.../:56-62`锛歚handleValidation` 鍙啓 `message`锛屼笉鍐?`code`
- **瀹炴祴**锛歚POST http://im-admin-service:3400/client/release-check -d '{}'` 鈫?`400 {"message":"must not be blank"}` 鈥斺€?**鏃㈡棤 `code`锛屾枃妗堣繕鏄嫳鏂?Bean Validation 榛樿鍊?*銆?- **寤鸿**锛氳ˉ `INVALID_ARGUMENT`锛屽苟璁?DTO 涓婄殑 `message` 鐢ㄤ腑鏂囥€?- **涓ラ噸搴?*锛氫腑

---

### 2.3 浣庯紙Low锛?
| # | 鏈嶅姟 | 鏂囦欢:琛?| 鐜拌薄 | 寤鸿 | 涓ラ噸搴?|
|---|---|---|---|---|---|
| L-1 | platform-identity | `.../identity/.../handler/GlobalExceptionHandler.java:27-33` | `handleIllegalArgument` 鎶?`ex.getMessage()` 鍘熸牱鍥炴樉锛堝彲鑳芥槸鍐呴儴瑙ｆ瀽淇℃伅锛?| 鍥哄畾鏂囨 + 璁版棩蹇?| 浣?|
| L-2 | platform-order | `.../order/.../handler/GlobalExceptionHandler.java:66-80` | `httpStatusFor` 鐢?`switch` 纭紪鐮佺爜琛紝涓庡叾瀹冩湇鍔＄殑銆屾寜 ApiException.status銆嶄袱濂楁満鍒跺苟瀛橈紱鏂板鐮佽嫢蹇樿鐧昏浼氶潤榛樺彉 400 | 灏嗙爜鈫掔姸鎬佹敹鏁涘埌鍏变韩鏋氫妇/`HttpStatusCodes` | 浣?|
| L-3 | group-idaas | `.../idaas/.../handler/GlobalExceptionHandler.java:31-32` | 鏍￠獙娑堟伅鎷兼帴 `e.getField() + ": " + e.getDefaultMessage()`锛屽瓧娈靛悕锛堣嫳鏂囨妧鏈悕锛夋毚闇茬粰鏈€缁堢敤鎴?| 鍙敤 `getDefaultMessage()` | 浣?|
| L-4 | gateway | `RateLimitFilter.java:32` | 429 鐨?`code` 鏄暟瀛?| 缁熶竴涓?`RATE_LIMITED` 瀛楃涓茬爜 | 浣?|
| L-5 | 澶氭湇鍔?| `.../handler/GlobalExceptionHandler.java`锛坮esource/tenant/order锛?| 500 鏃ュ織鏃?`uri`/`traceId`/璇锋眰 id锛屽瀹炰緥涓嬫棤娉曞畾浣?| 鏃ュ織鍔?`request.getRequestURI()` | 浣?|
| L-6 | gateway | `SaasSessionAuthenticationFilter.java:179-187` | `contextToken` 鐨?`catch (Exception ignored) { return Optional.empty(); }` 鎶娿€孞SON 瑙ｆ瀽澶辫触銆嶄笌銆屾棤涓婁笅鏂囥€嶅悎骞讹紝鏈€缁堝澶栨姤 `SAAS_CONTEXT_REQUIRED`锛岀湡瀹炲師鍥狅紙浼氳瘽鏁版嵁鎹熷潖锛夋棤鏃ュ織 | 鍔?`log.warn` | 浣?|

---

## 3. 宸茶鑼冪殑璇佹嵁娓呭崟锛堟湇鍔?鈫?缁熶竴鍑哄彛浣嶇疆 + 瑕嗙洊鎯呭喌锛?
> 瑕嗙洊鎯呭喌鎸夈€屾槸鍚︽湁鍑哄彛 / 鏄惁鍏滃簳 `Exception` / 鍏滃簳鏄惁璁板爢鏍?/ 鍏滃簳鏄惁鍚?`code` / 鏄惁 `@Slf4j`銆嶄簲缁存爣娉ㄣ€?
### 3.1 瀹屽叏杈炬爣锛堝彲浣滀负妯℃澘锛屽缓璁叾浣欐湇鍔＄収鎶勶級

| 鏈嶅姟 | 缁熶竴鍑哄彛 | 瑕嗙洊 |
|---|---|---|
| **platform-tenant** | `platform-services/tenant/platform-tenant-service/src/main/java/io/openware/platform/tenant/handler/GlobalExceptionHandler.java`锛坄:23` `@RestControllerAdvice`锛宍:24` `@Slf4j`锛?| `BusinessException`(:27) 鈫?400锛沗ApiException`(:35) 鈫?鎸?status锛沗DataAccessException`(:50) 鈫?绉熸埛涓婁笅鏂囩己澶辫浆 **401**銆佸惁鍒?500+`DATABASE_ERROR`锛沗HttpMessageNotReadableException`(:66) 鈫?400 `REQUEST_BODY_INVALID`锛沗MissingServletRequestParameterException`(:75) 鈫?400 `REQUEST_PARAM_MISSING`锛沗Exception`(:85) 鈫?**500 `INTERNAL_ERROR` + `log.error(..., ex)`**銆傗渽 鍏ㄧ淮搴﹁揪鏍?|
| **platform-resource** | `platform-services/resource/platform-resource-service/src/main/java/io/openware/platform/resource/handler/GlobalExceptionHandler.java:22-24,26,34,49,65,74,84` | 涓?tenant 鍚屾瀯銆傗渽 鍏ㄧ淮搴﹁揪鏍?|

- **瀹炴祴鍗拌瘉**锛歚GET http://platform-resource-service:4120/admin/resources`锛堟棤绉熸埛涓婁笅鏂囷級鈫?`401 {"code":"TENANT_CONTEXT_REQUIRED","message":"缂哄皯鏈夋晥鐨勭鎴?闂ㄥ簵涓婁笅鏂?}`锛沗POST` 鍚岃矾寰?鈫?`403 {"code":"PERMISSION_DENIED","message":"缂哄皯鏉冮檺: resource.manage"}`銆?*杩欐鏄巻鍙蹭笂銆?03 閫€鍖栨垚 500銆嶇殑淇璇佹嵁锛岃闂鍦ㄨ繖涓や釜鏈嶅姟宸查棴鐜€?*

### 3.2 鏈夊嚭鍙ｄ絾缂哄彛鏄庣‘

| 鏈嶅姟 | 缁熶竴鍑哄彛 | 鏈夊嚭鍙?| 鍏滃簳 `Exception` | 鍏滃簳璁板爢鏍?| 鍏滃簳鍚?`code` | `@Slf4j` |
|---|---|---|---|---|---|---|
| platform-order | `.../order/.../handler/GlobalExceptionHandler.java:17-19` | 鉁?| 鉁?`:41` | 鉁?`:43` | 鉂?`:44` 鍙湁 message | 鉁?`:18` |
| common-media | `.../media/.../handler/GlobalExceptionHandler.java:14-15` | 鉁?| 鉁?`:38` | 鉂?| 鉂?| 鉂?|
| im-admin | `.../im/admin/.../handler/GlobalExceptionHandler.java:18-19` | 鉁?| 鉁?`:62` | 鉂?| 鉂?| 鉂?|
| im-conversation | `.../im/conversation/.../handler/GlobalExceptionHandler.java:16-17` | 鉁?| 鉁?`:53` | 鉂?| 鉂?| 鉂?|
| im-message | `.../im/message/.../handler/GlobalExceptionHandler.java:16-17` | 鉁?| 鉁?`:52` | 鉂?| 鉂?| 鉂?|
| im-user | `.../im/user/.../handler/GlobalExceptionHandler.java:17-18` | 鉁?| 鉁?`:69` | 鉂?| 鉂?| 鉂?|
| platform-admin | `.../admin/.../handler/GlobalExceptionHandler.java:12-13` | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| platform-customer | `.../customer/.../handler/GlobalExceptionHandler.java:14-15` | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| platform-identity | `.../identity/.../handler/GlobalExceptionHandler.java:14-15` | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| platform-marketing | `.../marketing/.../handler/GlobalExceptionHandler.java:14-15` | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| common-payment | `.../payment/.../handler/GlobalExceptionHandler.java:15-16` | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| common-payment-channel | `.../payment-channel/.../handler/GlobalExceptionHandler.java:12-13` | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| group-idaas | `.../idaas/.../handler/GlobalExceptionHandler.java:14-15` | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|

### 3.3 鏃犵粺涓€鍑哄彛

| 鏈嶅姟 | 鏈?REST Controller | 缁熶竴鍑哄彛 |
|---|---|---|
| common-audit | 鉁?`common-services/audit/.../api/controller/AuditController.java` | 鉂?|
| common-mail | 鉁?`common-services/mail/.../api/controller/MailController.java` | 鉂?|
| common-sms | 鉁?`common-services/sms/.../api/controller/SmsController.java` | 鉂?|
| gateways/gateway | 鉂岋紙0 涓?`@RestController`锛學ebFlux `GlobalFilter`锛?| N/A锛屼絾杩囨护鍣ㄨ嚜閫犻敊璇綋锛堣 M-4锛?|
| gateways/im-access-ws | 鉂岋紙0 涓?`@RestController`锛岀函 WebSocket锛?| N/A |

### 3.4 璺ㄥ垏闈㈠凡瑙勮寖鐨勯儴鍒嗭紙鍊煎緱鑲畾锛?
| 椤?| 浣嶇疆 | 缁撹 |
|---|---|---|
| Spring Security 璁よ瘉澶辫触缁熶竴 JSON | `sdk/infrastructure/.../security/RestAuthenticationEntryPoint.java:22-37` | 鉁?401 + `{code,message}`锛涒潓 message 鍥哄畾鑻辨枃 `"Unauthorized"`锛涒殸锔?鏈敞鍐?`accessDeniedHandler`锛?03 浠嶈蛋瀹瑰櫒閿欒椤?|
| 鍐呴儴鏈嶅姟璁よ瘉澶辫触 | `sdk/infrastructure/.../security/InternalServiceAuthenticationFilter.java:61` | 鉁?鍙甯冨皵锛屼笉娉勯湶绛惧悕/瀵嗛挜 |
| 鏃ュ織瀹炵幇缁熶竴 | 鍏ㄤ粨搴?`src/main/java` | 鉁?`LoggerFactory.getLogger` / `import org.slf4j.Logger;` / `Logger x =` **鍚?0 鍛戒腑**锛?06 涓被甯?`@Slf4j`锛?04 涓湁鏃ュ織璋冪敤鐨勭被**鍏ㄩ儴**宸?`@Slf4j`銆俙validate-java-logging-conventions.ps1` **褰撳墠閫氳繃** |
| `printStackTrace` | 鍏ㄤ粨搴?| 鉁?0 鍛戒腑锛宍validate-java-exception-logging.ps1` 閫氳繃 |
| 閿欒鐮侀鏍?| SaaS 渚?141 涓爜 | 鉁?**100% 绗﹀悎 `^[A-Z][A-Z0-9_]*$`**锛屾棤澶у皬鍐欐贩鏉?|
| 缃戝叧闄愭祦澶辫触寮€鏀?| `RateLimitFilter.java:93-101` | 鉁?Redis 涓嶅彲鐢?fail-open 涓?`log.warn` 甯?path/key/寮傚父 |

---

## 4. i18n / 鏂囨涓嶄竴鑷存竻鍗曪紙浣嶇疆 鈫?鐜扮姸 鈫?寤鸿锛?
### 4.1 鍚庣锛氬悓涓€璇箟銆屼笂涓嬫枃缂哄け銆嶇敤浜?5 涓爜 + 2 绉嶇姸鎬佺爜

| 浣嶇疆 | 鐜扮姸锛坈ode / message / status锛?| 寤鸿缁熶竴鍊?|
|---|---|---|
| `gateways/gateway/.../security/SaasSessionAuthenticationFilter.java:87` | `SAAS_CONTEXT_REQUIRED` / `SaaS session is required` / 401 | `TENANT_CONTEXT_REQUIRED` / `缂哄皯杩愯惀涓婁笅鏂嘸 / 401 |
| `platform-services/admin/.../api/controller/StaffController.java:107` | `SAAS_CONTEXT_REQUIRED` / `璇峰厛閫夋嫨杩愯惀涓婁笅鏂嘸 / 401 | 鍚屼笂 |
| `platform-services/admin/.../api/controller/MediaImageController.java:65` | `SAAS_CONTEXT_REQUIRED` / `璇峰厛閫夋嫨杩愯惀涓婁笅鏂嘸 / 401 | 鍚屼笂 |
| `platform-services/admin/.../api/controller/StaffController.java:113` | `SAAS_CONTEXT_INVALID` / `杩愯惀涓婁笅鏂囧凡杩囨湡锛岃鍒锋柊椤甸潰` / 401 | `TENANT_CONTEXT_EXPIRED` / 401 |
| `platform-services/admin/.../infra/CustomerServiceClient.java:100` | `TENANT_CONTEXT_REQUIRED` / `璇峰厛閫夋嫨缁忚惀涓婁笅鏂嘸 / **409** | `TENANT_CONTEXT_REQUIRED` / **401** |
| `platform-services/admin/.../infra/RestKtvConfigDomainClient.java:286` | `TENANT_CONTEXT_REQUIRED` / 鍚屼笂 / **409** | 鍚屼笂 |
| `platform-services/admin/.../infra/ReservationDomainClient.java:103` | `TENANT_CONTEXT_REQUIRED` / 鍚屼笂 / **409** | 鍚屼笂 |
| `platform-services/order/.../api/controller/OrderController.java:253` | `TENANT_CONTEXT_REQUIRED` / `缂哄皯鏈夋晥鐨勭鎴?缁勭粐/闂ㄥ簵涓婁笅鏂嘸 / 401 | `TENANT_CONTEXT_REQUIRED` / 401 |
| `platform-services/order/.../api/controller/ReservationController.java:108`銆乣ReservationCompatController.java:35`銆乣KtvPricingController.java:32`銆乣CatalogController.java:61,172`銆乣OrderItemController.java:78` | `TENANT_CONTEXT_MISSING` / `缂哄皯绉熸埛涓婁笅鏂嘸 / 401 | 鈫?`TENANT_CONTEXT_REQUIRED` |
| `platform-services/order/.../application/ReservationApplicationService.java:291` | `TENANT_CONTEXT_MISSING` / 401 | 鍚屼笂 |
| `platform-services/order/.../application/ProductApplicationService.java:158`銆乣InventoryApplicationService.java:182`銆乣ProductCategoryApplicationService.java:178` | `TENANT_CONTEXT_REQUIRED` / `缂哄皯鏈夋晥闂ㄥ簵涓婁笅鏂嘸 / 400锛坄BusinessException` 鎭?400锛?*涓庡悓涓?401 鐨勫叾瀹冨疄鐜颁笉涓€鑷?*锛?| `TENANT_CONTEXT_REQUIRED` / 401 |
| `platform-services/tenant/.../api/controller/TenantConfigController.java:68` | `TENANT_CONTEXT_MISSING` / 401 | 鈫?`TENANT_CONTEXT_REQUIRED` |
| `platform-services/admin/.../api/controller/ReportController.java:171` | `TENANT_CONTEXT_MISSING` / 401 | 鈫?`TENANT_CONTEXT_REQUIRED` |
| `common-services/payment/.../application/PaymentMethodApplicationService.java:142`銆乣api/controller/InternalPaymentQueryController.java:45` | `TENANT_CONTEXT_MISSING` / 401 | 鈫?`TENANT_CONTEXT_REQUIRED` |
| `common-services/payment/.../api/controller/CollectController.java:31` | **`AUTH_CONTEXT_EXPIRED` / `缂哄皯绉熸埛涓婁笅鏂嘸 / 401** 鈥斺€?**鐮佷笌璇箟涓嶇**锛堢爜璇淬€岃璇佽繃鏈熴€嶏紝娑堟伅璇淬€岀己涓婁笅鏂囥€嶏級 | `TENANT_CONTEXT_REQUIRED` |
| `platform-services/tenant/.../handler/GlobalExceptionHandler.java:54`銆乣platform-services/resource/.../handler/GlobalExceptionHandler.java:53` | `TENANT_CONTEXT_REQUIRED` / `缂哄皯鏈夋晥鐨勭鎴?闂ㄥ簵涓婁笅鏂嘸 / 401 | 鉁?淇濇寔 |

**缁撹**锛氬悓涓€璇箟 鈫?**5 涓爜**锛坄SAAS_CONTEXT_REQUIRED` / `SAAS_CONTEXT_INVALID` / `TENANT_CONTEXT_REQUIRED` / `TENANT_CONTEXT_MISSING` / `AUTH_CONTEXT_EXPIRED`锛? **3 绉嶇姸鎬?*锛?01 / 409 / 400锛? **6 绉?message 鎺緸**銆傚墠绔?`adminErrorMessage.js:33,36,37` 鍥犳瑕佸悓鏃舵槧灏勪笁涓爜锛宍c-end/auth-errors.js:10` 鍙堣鏄犲皠绗洓涓?鈥斺€?鏄犲皠琛ㄥ繀鐒舵紓绉汇€?
### 4.2 鍚庣锛氳嫳鏂囪８鏂囨锛堝澶?message 蹇呴』鏄畝浣撲腑鏂囷級

**鎬婚噺锛氬叏浠撳簱 292 鏉?*锛堟棤 CJK 瀛楃涓旈潪绾爜瀛楅潰閲忕殑 `ApiException` / `BusinessException` message锛夈€傛寜鏈嶅姟锛?
| 鏈嶅姟 | 鏉℃暟 |
|---|---|
| im-user | 112 |
| im-message | 55 |
| im-conversation | 47 |
| common-media | 30 |
| im-admin | 24 |
| platform-admin | 24锛堝惈 im-admin 褰掑睘锛屾寜鐩綍璁?24锛?|
| platform-identity | 14 |
| group-idaas | 5 |
| platform-order | 3 |
| common-payment-channel | 1 |
| platform-customer | 1 |

**SaaS 闈紙platform-services + common-services + group-services锛夐€愭潯 鈥斺€?杩?54 鏉℃槸浼樺厛绾ф渶楂樼殑锛?*

| 浣嶇疆 | 鐜扮姸 | 寤鸿 |
|---|---|---|
| `platform-services/identity/.../api/controller/AuthContextController.java:46`銆乣:77` | `"missing or invalid CSRF token"`锛?03 `CSRF_TOKEN_INVALID`锛墊 `CSRF 浠ょ墝缂哄け鎴栧凡杩囨湡` |
| `platform-services/identity/.../api/controller/AuthContextController.java:85` | `"SaaS session is required"`锛?01 `SAAS_SESSION_REQUIRED`锛墊 `鐧诲綍鐘舵€佸凡澶辨晥锛岃閲嶆柊鐧诲綍` |
| `platform-services/identity/.../infra/security/AccountTokenResolver.java:25,29,36` | `"Bearer token is required"` / `"Bearer token is invalid or expired"` | `缂哄皯璁块棶浠ょ墝` / `璁块棶浠ょ墝鏃犳晥鎴栧凡杩囨湡` |
| `platform-services/identity/.../application/OAuthImBindApplicationService.java:88,100` | `"IM userinfo missing open_id"` / `"IM bind conflict"` | `IM 鐢ㄦ埛淇℃伅缂哄皯 open_id` / `IM 璐﹀彿宸茶缁戝畾` |
| `platform-services/admin/.../application/AdminLoginApplicationService.java:37` | `"invalid username or password"`锛?01 `ADMIN_LOGIN_INVALID`锛?*瀹炴祴杩斿洖** | `鐢ㄦ埛鍚嶆垨瀵嗙爜閿欒` |
| `platform-services/admin/.../application/AdminPasswordApplicationService.java:23,26` | `"missing SaaS admin session"` / `"invalid or expired SaaS admin session"` | `缂哄皯骞冲彴璐﹀彿浼氳瘽` / `骞冲彴璐﹀彿浼氳瘽宸插け鏁坄 |
| `platform-services/admin/.../application/SsoAuthApplicationService.java:37,47`銆乣infra/IdaasSsoClient.java:43` | `"invalid or expired SSO ticket"` / `"group session expired"` | `SSO 绁ㄦ嵁鏃犳晥鎴栧凡杩囨湡` / `闆嗗洟浼氳瘽宸茶繃鏈焋 |
| `platform-services/customer/.../api/controller/MyAssetsController.java:57`銆乣platform-services/order/.../api/controller/MyOrderController.java:47`銆乣platform-services/order/.../api/controller/ReservationController.java:116` | `"missing authenticated account"`锛?01 `ACCOUNT_REQUIRED`锛?| `缂哄皯宸茶璇佽处鍙穈 |
| `platform-services/order/.../api/controller/MyOrderController.java:51` | `"customer profile is not initialized"` | `浼氬憳妗ｆ灏氭湭鍒濆鍖朻 |
| `common-services/payment-channel/.../application/ChannelProviderApplicationService.java:57,64,68` | `"payment channel disabled: " + ...` / `"missing payment channel provider"` / `"unknown payment channel provider: " + provider` | 涓枃锛堜笖绗?3 鏉℃槸鎷兼帴娑堟伅锛岃 4.3锛?|
| `common-services/media/.../media/*.java`锛?0 鏉★紝`MediaObjectService:35,45,51,70,73`銆乣MediaUploadSessionService:46,79,83,105,141,165,170,173`銆乣MediaMultipartUploadService:31,36,76,99,117,119,132`銆乣MediaReferenceService:36,80,82`銆乣MinioMediaStorage:106,120,151`銆乣OssMediaStorage:102`銆乣ReleaseArtifactService:31,43,51`锛?| 鍏ㄩ儴鑻辨枃锛坄"Media object not found"` / `"Idempotency key does not match the upload request"` 绛夛級 | 鎵归噺涓枃 |
| `group-services/idaas/.../application/AuthApplicationService.java:47,78,80`銆乣infra/security/GroupJwtSigner.java:64`銆乣infra/security/IdaasSessionStore.java:61` | `"invalid username or password"` / `"invalid or expired SSO ticket"` / `"group session expired"` / `"invalid or expired sso token"` / `"invalid or expired session"` | 涓枃 |

**缃戝叧杩囨护鍣ㄥ唴缃嫳鏂?*锛坓rep 涓嶅埌 `ApiException`锛屼絾纭疄瀵瑰锛夛細

| 浣嶇疆 | 鐜扮姸 | 寤鸿 |
|---|---|---|
| `gateways/gateway/.../security/SaasSessionAuthenticationFilter.java:204` | `{"code":"SAAS_SESSION_REQUIRED","message":"SaaS session is required"}`锛?*瀹炴祴杩斿洖**锛?| 涓枃 |
| `sdk/infrastructure/.../security/RestAuthenticationEntryPoint.java:29,32` | `{"code":"UNAUTHORIZED","message":"Unauthorized"}`锛?*瀹炴祴杩斿洖**锛?| 涓枃 `鏈璇佹垨鐧诲綍鐘舵€佸凡澶辨晥` |

### 4.3 鍚庣锛氭嫾鎺?/ 鍥炴樉鍨嬫秷鎭?
| 浣嶇疆 | 鐜扮姸 | 闂 | 寤鸿 |
|---|---|---|---|
| `platform-services/admin/.../api/controller/ContextController.java:69` | `"contextId 鏍煎紡闈炴硶: " + request.contextId()` | 鍥炴樉鐢ㄦ埛杈撳叆锛堝惈涓嫳娣锋帓锛?| `contextId 鏍煎紡闈炴硶锛屽簲涓?tenant:org:store` |
| `sdk/infrastructure/.../tenant/PermissionGuard.java:13` | `"缂哄皯鏉冮檺: " + permissionCode` | 鎶€鏈爜鐩村嚭缁欒繍钀ヤ汉鍛?| 鏄犲皠涓烘潈闄愪腑鏂囧悕锛屾垨淇濈暀鐮佷絾缁熶竴鏍煎紡 |
| `common-services/payment/.../application/CollectApplicationService.java:202` | `new ApiException(422, "PAYMENT_CHANNEL_DISABLED", "鏈煡鏀粯鏂瑰紡: " + p.method())` | **鐮佽銆屾笭閬撴湭鍚敤銆嶏紝娑堟伅璇淬€屾湭鐭ユ敮浠樻柟寮忋€嶁€斺€旂爜涓庢秷鎭煕鐩?* | 浜岃€呭榻愶紙`PAYMENT_METHOD_UNKNOWN` 鎴栨敼娑堟伅锛?|
| `common-services/payment/.../application/CollectApplicationService.java:119,357`銆乣PaymentMethodApplicationService.java:136` | `"鏀粯鏂瑰紡鏈巿鏉? " + p.method()` / `"浣跨敤 " + method + " 鎶垫墸蹇呴』鎸囧畾浼氬憳(customerId)"` / `"鏈煡鏀粯鏂瑰紡: " + method` | 涓嫳鎷彿娣锋帓 `(customerId)` | 缁熶竴涓枃鎷敞銆屼細鍛樸€?|
| `common-services/payment-channel/.../ChannelProviderApplicationService.java:57,68` | 鑻辨枃 + 鎷兼帴 provider | 瑙?4.2 | 涓枃 |
| `platform-services/tenant/.../handler/GlobalExceptionHandler.java:80`銆乣platform-services/resource/.../handler/GlobalExceptionHandler.java:79` | `"缂哄皯璇锋眰鍙傛暟: " + ex.getParameterName()` | 鍙傛暟鍚嶄负鑻辨枃鎶€鏈悕 | `缂哄皯璇锋眰鍙傛暟`锛堟妧鏈悕鍙繘鏃ュ織锛?|
| `common-services/media/.../handler/GlobalExceptionHandler.java:35` | `"Required request header is missing: " + exception.getHeaderName()` | 鑻辨枃 + 鎷兼帴 | 涓枃 |
| `common-services/media/.../media/MediaUploadSessionService.java:161` | `"鏂囦欢澶у皬瓒呭嚭闄愬埗锛? + mediaKindLabel(...)` | 涓枃浣嗘嫾鎺ュ姩鎬佺墖娈碉紝闀垮害涓嶅彲鎺?| 鍙帴鍙楋紝寤鸿妯℃澘鍖?|

### 4.4 鍚庣 code 鈫?鍓嶇鏄犲皠婕傜Щ锛堣法鏈嶅姟锛?
| 椤?| 鐜扮姸 | 寤鸿 |
|---|---|---|
| `open-saas-admin/src/utils/adminErrorMessage.js:15-40` | 鏄犲皠 21 涓爜锛?*0 涓槸骞界伒鐮?*锛堝悗绔兘鐪熷疄瀛樺湪锛夆渽锛涗絾鍚庣 141 涓爜涓?**120 涓棤鏄犲皠** | 鐢卞悗绔鍑虹爜琛紙OpenAPI/甯搁噺锛夐┍鍔ㄥ墠绔槧灏?|
| 鍏朵腑 **18 涓?*銆屾棤鏄犲皠 **涓?* 鑻辨枃 message銆嶁€斺€斿悗鍙颁細閫€鍖栨垚鐘舵€佺爜娉涘寲鏂囨 | `ACCOUNT_REQUIRED`銆乣ADMIN_LOGIN_INVALID`銆乣ADMIN_SESSION_INVALID`銆乣ADMIN_SESSION_MISSING`銆乣AUTH_INVALID_CREDENTIAL`銆乣AUTH_INVALID_SESSION`銆乣AUTH_INVALID_TOKEN`銆乣AUTH_TOKEN_INVALID`銆乣AUTH_TOKEN_REQUIRED`銆乣CLIENT_RELEASE_PLATFORM_NOT_ENABLED`銆乣IM_BIND_CONFLICT`銆乣IM_OPEN_ID_MISSING`銆乣MEMBER_NOT_FOUND`銆乣PAYMENT_CHANNEL_DISABLED`銆乣PAYMENT_CHANNEL_UNKNOWN`銆乣SAAS_SESSION_REQUIRED`銆乣SSO_TICKET_EXPIRED`銆乣SSO_TICKET_INVALID` | 鑻辨枃鏀逛腑鏂?+ 琛ユ槧灏?|
| **瀹炴祴** | `POST /api/v1/admin/auth/login`锛堥敊瀵嗙爜锛夆啋 `401 {"message":"invalid username or password","code":"ADMIN_LOGIN_INVALID"}` 鈫?鍚庡彴 `CJK_PATTERN` 涓嶉€氳繃 鈫?璧?401 鍏滃簳 鈫?杩愯惀鐪嬪埌**銆岀櫥褰曠姸鎬佸凡澶辨晥锛岃閲嶆柊鐧诲綍銆傘€?* | 鈥?|
| `open-saas-mobile/c-end/auth-errors.js:5-39` | 鏄犲皠 7 涓爜锛屽叾涓?**5 涓悗绔粠鏈骇鐢?*锛歚NO_CONSUMER_ACCESS`銆乣CONTEXT_SELECTION_REQUIRED`銆乣IM_SESSION_EXPIRED`銆乣IM_BRIDGE_AUTH_FAILED`銆乣PKCE_UNAVAILABLE`锛堝叏浠撳簱 grep 0 鍛戒腑锛涘彧鏈?`IAM_CONTEXT_UNAVAILABLE` 涓?`SAAS_SESSION_REQUIRED` 鐪熷疄瀛樺湪锛?| 鍒犻櫎骞界伒鐮佹垨琛ラ綈鍚庣浜у嚭 |

### 4.5 鍓嶇锛歩18n 鍩虹璁炬柦锛堢粨璁猴細涓嶅瓨鍦級

| 浠撳簱 | i18n 渚濊禆 | locale 鏂囦欢 | 缁撹 |
|---|---|---|---|
| `open-saas-admin` | 鉂?鏃狅紙渚濊禆浠?`element-plus` / `axios` / `pinia` / `vue` / `vue-router`锛?| 鉂?鏃?| 鍏ㄩ噺纭紪鐮佷腑鏂囷紱**鏃?key 瑕嗙洊闂鍙█** |
| `open-saas-mobile`锛坄src/b-end`锛?| 鉂?鏃?| 鉂?鏃?| 鍚屼笂 |
| `open-saas-mobile`锛坄c-end`锛岀函 JS锛?| 鉂?鏃?| 鉂?鏃?| 鍚屼笂锛涘敮涓€銆宭ocale銆嶇棔杩规槸 `app.js:39,200,204,222,227,242,248,756,760` 鐨?`toLocaleString('zh-CN')` |

**鈿狅笍 浜у搧淇″彿**锛歚open-saas-admin/src/views/platform/tenants.vue:30-31` 宸叉湁 **銆岄粯璁よ瑷€銆嶃€岄粯璁ゆ椂鍖恒€?* 涓や釜绉熸埛閰嶇疆椤癸紙`form.defaultLocale` / `form.defaultTimezone`锛夛紝璇存槑澶氳瑷€鏄凡瑙勫垝鐨勪骇鍝佽兘鍔涳紱浣嗗墠绔病鏈変换浣曟壙杞藉畠鐨勬満鍒躲€?*杩欐槸鏈瀹¤鏈€闇€瑕佷骇鍝佸喅绛栫殑涓€椤?*锛氬厛瀹氥€屽璇█鏄惁 2.0 鑼冨洿銆嶏紝鍐嶅喅瀹氭槸寮曞叆 `vue-i18n` 鎶藉彇锛岃繕鏄槑纭彧鏀寔绠€浣撲腑鏂囧苟鎶婅繖浜涢厤缃」鏍囨敞涓恒€岄鐣欍€嶃€?
### 4.6 鍓嶇锛氭湳璇笉涓€鑷?
鍑虹幇娆℃暟锛堜笉鍚?`node_modules`/`dist`锛夛細

| 鏈 | open-saas-admin/src | mobile/src(b-end) | mobile/c-end |
|---|---|---|---|
| 鍖呭帰 | 56 | 21 | 31 |
| 璧勬簮 | 19 | 3 | 0 |
| 鐐瑰崟鐩綍 | 11 | 2 | 2 |
| 鍟嗗搧 | 35 | 5 | 11 |
| 搴旀敹 | 25 | 10 | 0 |
| 宸叉敹 | 7 | 5 | 0 |
| **寰呮敹** | 0 | **1** | 0 |
| 鍌ㄥ€?| 23 | 8 | 4 |
| 鍏呭€?| 14 | 2 | 2 |
| 浣欓 | 10 | 2 | 2 |
| 闂ㄥ簵 | 80 | 10 | 10 |
| **鍟嗘埛** | 2锛堜粎娉ㄩ噴锛?| **3锛堢敤鎴峰彲瑙侊級** | 0 |
| **鍟嗗** | 0 | 0 | **1锛堢敤鎴峰彲瑙侊級** |
| 绉熸埛 | 67 | 8 | 8 |

| 浣嶇疆 | 鐜扮姸 | 寤鸿 |
|---|---|---|
| `open-saas-admin/src/router/index.js:71` | `meta: { title: '鍖呭帰/璧勬簮' }` 鈥斺€?**鑿滃崟鏍囬鐢ㄦ枩鏉犲苟鍒椾袱涓瘝**锛岃鏄庢湳璇湭瀹氱 | 浜у搧纭鍚庢嫨涓€锛堝缓璁敤鎴烽潰銆屽寘鍘€嶃€佷唬鐮侀潰 `resource`锛?|
| `open-saas-mobile/src/b-end/views/Orders.vue:58` | `路 寰呮敹 楼{{ fenToYuan(orderBill(order).payableAmount) }}`锛屽悓鏂囦欢 `:111,:137,:226` 鐢ㄣ€屽簲鏀躲€?| 缁熶竴銆屽簲鏀躲€?|
| `open-saas-mobile/src/b-end/App.vue:5,49,64` | 鐢ㄦ埛鍙 **銆孉380 鍟嗘埛宸ヤ綔鍙般€?銆孉380 鍟嗘埛绔€?*锛岃€?`orders.vue:116,425` 鐢ㄣ€岀鎴枫€嶃€乤dmin 鐢ㄣ€岀鎴峰悗鍙般€?| 缁熶竴锛堝缓璁?B 绔О銆屽晢鎴风銆嶆垨銆岃繍钀ョ銆嶏紝骞跺湪 admin 淇濇寔涓€鑷达級 |
| `open-saas-mobile/c-end/app.js:625` | `'璇疯仈绯诲晢瀹跺紑閫氭敮浠樻柟寮?`锛堝晢瀹讹級锛屽悓鏂囦欢 `:74,:307,:453` 鐢ㄣ€岄棬搴椼€?| 缁熶竴銆岄棬搴椼€?|
| `open-saas-admin/src/constants/payment-methods.js:4,11,22` | `'鍌ㄥ€煎竵(A380甯?'` / `'鍌ㄥ€煎竵'` / `'鍌ㄥ€?` | 瑙?4.7 |

### 4.7 鍓嶇锛氬偍鍊煎搧鐗屽悕锛堟槑纭繚鍙嶄唬鐮佸唴鑷堪瑙勫垯锛?
| 浣嶇疆 | 鐜扮姸 |
|---|---|
| `open-saas-admin/src/constants/payment-methods.js:14-15` | **瑙勫垯鍘熸枃**锛?銆屽偍鍊兼槸鍝佺墝灞曠ず鍚嶏紝鍙栫鎴烽厤缃?`tnt_tenant_config.wallet_brand_name`锛堥粯璁?A380甯侊級锛屼笉寰楃‖缂栫爜銆? |
| `open-saas-admin/src/views/tenant/wallet.vue:114,123,140` | 纭紪鐮?`'A380甯?`锛堝惈 `const tokenName = ref('A380甯?)`銆乣configForm = { brandName: 'A380甯?, ... }`锛?|
| `open-saas-admin/src/views/tenant/wallet.vue:82` | placeholder `濡?A380甯乣 |
| `open-saas-admin/src/views/tenant/payments.vue:108` | `const walletBrand = ref('鍌ㄥ€?)` 鈫?榛樿鍊间笌瑙勫垯涓嶇 |
| `open-saas-admin/src/views/tenant/orders.vue:535` | `const walletBrand = ref('鍌ㄥ€?)` 鈫?鍚屼笂锛坄:1182` 鎵嶈閰嶇疆瑕嗙洊锛?|
| `open-saas-admin/src/views/tenant/ktv-config.vue:5,88,121,493`銆乣api/payment.js:70`銆乣api/ktv.js:38` | `A380甯乣 纭紪鐮?|
| `open-saas-admin/src/router/index.js:137` | 鑿滃崟鍚嶃€屽偍鍊肩鐞嗐€?|

**寤鸿**锛氬叏閮ㄦ敼涓鸿鍙栧悓涓€ store 涓殑 `walletBrand`锛堝垵鍊肩敤閰嶇疆鎴栫┖瀛楃涓诧紝閬垮厤棣栧抚闂?`'鍌ㄥ€?`锛夛紱`payment-methods.js:4` 鐨勯暱鍚嶇粰璁剧疆椤电敤锛宍methodLabel` 鐨勯粯璁ゅ€兼敼涓哄繀浼犮€?
### 4.8 鍓嶇锛氶噾棰濆崟浣嶄笌鏍煎紡

| 浣嶇疆 | 鐜扮姸 | 闂 |
|---|---|---|
| `open-saas-admin/src/utils/format.js:16-21` | `/** 鏈€灏忚揣甯佸崟浣嶏紙鍒嗭級鈫?\`楼 12.00\`銆?*/ return '楼 ' + (number / 100).toFixed(2)` | **瑙勮寖瀹炵幇瀛樺湪** |
| `views/tenant/orders.vue:818` | `'楼 ' + (n / 100).toFixed(2)` | 閲嶅瀹炵幇锛堟湁绌烘牸锛?|
| `views/tenant/orders.vue:824` | `'楼 ' + (n / 100).toLocaleString('zh-CN', {...})` | 閲嶅瀹炵幇锛堝甫鍗冨垎浣嶏級鈫?**鍚屼竴椤甸潰涓ょ閲戦鏍煎紡** |
| `views/tenant/orders.vue:376` | 妯℃澘鍐呰仈 `楼{{ (Number(row.unitPrice) / 100).toFixed(2) }}` | 鏃犵┖鏍?|
| `views/tenant/products.vue:38` | 妯℃澘鍐呰仈 `楼{{ (Number(row.salePrice) / 100).toFixed(2) }}` | 鏃犵┖鏍?|
| `views/tenant/payments.vue:138` | `'楼 ' + (Number(v) / 100).toFixed(2)` | 閲嶅瀹炵幇 |
| `views/tenant/shift.vue:174` | `'楼 ' + (n / 100).toFixed(2)` | 閲嶅瀹炵幇 |
| `views/tenant/reservations.vue:75` | `'楼' + (Number(pricing.roomUnitPrice || 0) / 100).toFixed(2)` | 鏃犵┖鏍?|
| `views/tenant/members.vue:150` | `(Number(v) / 100).toFixed(2) + ' 鍏?` | **鍚庣紑銆屽厓銆嶏紝涓庡叾瀹冨叏閮ㄣ€屄?鍓嶇紑銆嶇浉鍙?* |
| `views/tenant/ktv-config.vue:270-272` | 鏈湴 `fenToYuan(v)` | 鍙堜竴浠介噸澶嶅疄鐜?|
| `views/tenant/ktv-config.vue:65,223` | `閫掑绮掑害(鍒?` | **銆屽垎銆嶅湪姝よ〃绀哄垎閽?*锛屽悓涓€鏂囦欢 `:270` 鐨勩€屽垎銆嶈〃绀哄垎甯?鈫?鏈鍐茬獊 |
| `views/tenant/reports.vue:126` | `h + ' 灏忔椂 ' + m + ' 鍒?` | 銆屽垎銆? 鍒嗛挓 |
| `views/tenant/orders.vue:1068,1111,1175`銆乣payments.vue:136`銆乣shift.vue:217,239`銆乣ktv-config.vue:342,343,418` | `Math.round(x * 100)` 鍚勮嚜瀹炵幇銆屽厓鈫掑垎銆?| 鑷冲皯 8 澶勯噸澶嶏紱寤鸿鏀舵暃鍒?`yuanToMinor` |

**寤鸿**锛氣憼 鍏ㄩ儴鏀硅皟 `src/utils/format.js` 鐨?`formatMoney`锛屽苟缁熶竴涓?`楼 12.00`锛堝墠缂€ + 绌烘牸 + 涓や綅灏忔暟 + 鍗冨垎浣嶏級锛涒憽 鏂板 `yuanToMinor` / `minorToYuan` 鍒?`format.js`锛屽垹闄?8 澶勫唴鑱旓紱鈶?銆屽垎銆嶈〃绀哄垎閽熺殑鍦版柟鏀瑰啓涓恒€屽垎閽熴€嶏紙`閫掑绮掑害(鍒嗛挓)`锛夈€?
### 4.9 鍓嶇锛氭椂闂存牸寮?
| 浣嶇疆 | 鐜扮姸 |
|---|---|
| `views/tenant/orders.vue:615` | 鍞竴涓€澶勬牸寮忓寲锛歚new Date(x).toLocaleTimeString('zh-CN', { hour12: false })` |
| `views/platform/tenants.vue:19` | `prop="createdAt" label="鍒涘缓鏃堕棿"` 鈥斺€?**鐩存帴娓叉煋鍚庣鍘熷鍊?* |
| `views/tenant/shift.vue:64` | `prop="businessDate" label="钀ヤ笟鏃ユ湡"` 鈥斺€?鍘熷鍊?|
| `views/tenant/ktv-config.vue:164` | `prop="createdAt" label="鏃堕棿"` 鈥斺€?鍘熷鍊?|
| `open-saas-mobile/src/b-end` | 鍏ㄧ洰褰?**0 澶?* 鏃ユ湡鏍煎紡鍖栬皟鐢?|
| `open-saas-mobile/c-end/app.js` | 9 澶?`toLocaleString('zh-CN')`锛堜粎閲戦/鏁板€硷紝鏃犳樉寮忔棩鏈熸牸寮忥級 |

**寤鸿**锛氭柊澧?`formatDateTime` / `formatDate`锛坄YYYY-MM-DD HH:mm` / `YYYY-MM-DD`锛夊埌鍏变韩 utils锛屾浛鎹㈡墍鏈夊師濮嬪€兼覆鏌擄紱閬垮厤瑁?`toLocaleString()`锛堟棤 locale 鍙傛暟鏃朵緷璧栬繍琛岀幆澧冿級銆?
---

## 5. 寤鸿鐨勪慨澶嶆壒娆′笌鍙墽琛岄獙璇佸懡浠?
### 鎵规 1 鈥?绾満姊般€佸彲涓€娆℃敼瀹屻€侀浂浜у搧渚濊禆锛堝缓璁湰鍛級

| # | 鍐呭 | 娑夊強鏂囦欢鏁?| 椋庨櫓 |
|---|---|---|---|
| 1.1 | 缁?`common-audit` / `common-mail` / `common-sms` 澧炲姞 `GlobalExceptionHandler`锛堢収鎶?`platform-resource` 鐗堟湰锛屽惈 `Exception` 鍏滃簳 + `@Slf4j`锛?| 3 鏂版枃浠?| 鏋佷綆 |
| 1.2 | 4 涓?IM 鏈嶅姟鐨勫厹搴曞幓鎺?`ex.getMessage()`锛屾敼涓?`{"code":"INTERNAL_ERROR","message":"鏈嶅姟鍐呴儴閿欒锛岃绋嶅悗閲嶈瘯"}` + `log.error(..., ex)` + 琛?`@Slf4j` | 4 | 浣?|
| 1.3 | `platform-order` / `common-media` 鍏滃簳琛?`code: INTERNAL_ERROR` 骞舵敼涓枃锛沵edia 鐨?`badRequest` 琛?`code` | 2 | 浣?|
| 1.4 | 8 涓湇鍔＄殑 advice 澧炶ˉ妗嗘灦寮傚父鐨勬樉寮忔槧灏勶紙`NoResourceFoundException`鈫?04銆乣HttpRequestMethodNotSupportedException`鈫?05銆乣HttpMediaTypeNotSupportedException`鈫?15銆乣MissingRequestHeaderException`鈫?00锛夛紝閬垮厤琚?`Exception` 鍏滃簳鎴幏 | 8 | 浣庯紙绾柊澧炲垎鏀級 |
| 1.5 | 6 涓€屽彧鏈?ApiException銆嶇殑鏈嶅姟琛?`Exception` 鍏滃簳锛堟爣 1.2 褰㈡€侊級 | 6 | 浣?|
| 1.6 | 蹇呭～ `Idempotency-Key` 鏀逛负 `required=false`锛? 涓?Controller锛夛紝璁╁凡鏈夌殑 `IDEMPOTENCY_KEY_REQUIRED` 鐢熸晥 | 5 | 浣?|
| 1.7 | `AccountApplicationService.java:154` 鏀规姏 `ApiException(401,"AUTH_INVALID_CREDENTIAL", ...)` | 1 | 浣?|
| 1.8 | `order/ReportController.java:42,68,90` 鏀规姏 `ApiException(401,"TENANT_CONTEXT_MISSING", ...)` | 1 | 浣?|
| 1.9 | 缃戝叧 429 鐨?`code` 鏀瑰瓧绗︿覆锛沗HttpStatusCodes` 琛?`TOO_MANY_REQUESTS = 429`銆乣UNPROCESSABLE_CONTENT = 422` | 2 | 浣?|
| 1.10 | 鍓嶇锛氬叏閮ㄩ噾棰濊蛋 `utils/format.js`锛涙柊澧?`yuanToMinor`锛涙敼鎺?`members.vue:150` 鐨勩€屽厓銆嶅悗缂€锛沗銆岄€掑绮掑害(鍒?銆嶁啋銆岄€掑绮掑害(鍒嗛挓)銆峘 | ~10 | 浣庯紙鏈夋祴璇曞厹搴曪細`npm test`锛?|
| 1.11 | 鍓嶇锛氬偍鍊煎搧鐗屽悕鍘荤‖缂栫爜锛坄wallet.vue:114,123,140,82`锛沗payments.vue:108`銆乣orders.vue:535` 榛樿鍊硷級 | 3 | 浣?|
| 1.12 | 鍓嶇锛氭棩鏈熺粺涓€璧?`formatDateTime`锛沗c-end/auth-errors.js` 鍒犻櫎 5 涓菇鐏电爜 | ~6 | 浣?|
| 1.13 | **`open-saas-admin/src/views/tenant/orders.vue:410` 鍒犻櫎瀛楅潰 `楼 `**锛坄fmtCents` 宸插甫绗﹀彿锛夆啋 娑堥櫎 "楼 楼 123.45" | 1 | 鏋佷綆锛堝敮涓€纭畾鎬х殑 UI 鏂囨缂洪櫡锛?|
| 1.14 | **C 绔噾棰濆昂搴?*锛歚c-end/app.js:853` 鏀圭敤 `minorMoney(...)`锛沗:200,204,222,227,248,756,760` 琛?`/100`锛堟垨缁熶竴璧颁竴涓?`walletBalance()`锛夛紱鍒犻櫎姝讳唬鐮?`:240-242 fenToYuanStr` | 1 | 涓紙娑夊強閲戦锛屾敼鍚庨』浜哄伐鏍稿 mock 涓庣湡鏈哄悇涓€娆★級 |
| 1.15 | **C 绔?UTC 鏃ユ湡**锛歚c-end/app.js:33`銆乣:655` 寮冪敤 `toISOString().slice(0,10)`锛屾敼鏈湴鎷艰锛堟妱 `admin/views/tenant/shift.vue:161-163`锛夛紱`:314-316` 鐨?`scheduleAt` 闇€涓庡悗绔‘璁ゆ椂鍖鸿涔夊悗鍐嶆敼 | 1 | 涓紙`:314-316` 寰呭绾︾‘璁わ級 |
| 1.16 | 鍓嶇鏂囨鍘婚噸锛? 浠?`'鏈嶅姟绔湭杩斿洖 CSRF token'` 鈫?1 甯搁噺锛沗b-end/App.vue:82` 涓嶅啀閫忎紶 `e.message`锛屾敼璧?code鈫掍腑鏂囨槧灏?| ~6 | 浣?|
| 1.17 | `roles.vue:41,42` 涓嬫媺鏀逛腑鏂囷紙澶嶇敤 `staff.vue:161`锛夛紱`ktv-config.vue:219,220,232,233,234` 瑁告灇涓剧Щ鍒?`value` | 2 | 浣?|
| 1.18 | 鍚庣鏁忔劅鏃ュ織鑴辨晱锛歚SmtpPasswordResetMailSender.java:37`銆乣SmsPasswordResetSender.java:37`銆乣TencentSmsProvider.java:64,101,104`銆乣AliyunSmsProvider.java:54,87,90`锛堟墜鏈哄彿璧板凡鏈?`PhonePrivacy.mask`锛?| 4 | 浣庯紙绾棩蹇楋級 |
| 1.19 | 鍚庣闈欓粯 500 琛ユ棩蹇楋細`GlobalExceptionHandler` 鍏滃簳缁熶竴鍔?`log.error(..., ex)`锛? 涓?IM 鏈嶅姟 + media锛夛紱`AuditClient.java:75`銆乣MessageProjectionService.java:43,105,121` 鐢?`getMessage()`/`toString()` 鏀逛负浼?`ex` 鏈綋 | ~8 | 浣庯紙绾棩蹇楋級 |
| 1.20 | 鍚庣绌?catch 鑷冲皯琛ユ敞閲?鏃ュ織锛歚StaffAccountApplicationService.java:154,165`銆乣CollectApplicationService.java:349`銆乣PermissionSnapshotCache.java:38,55,61,69,77`銆乣ResourceStateClient.java:130,166,186` | ~6 | 浣庯紙琛屼负涓嶅彉锛屼粎鍔犲彲瑙傛祴鎬э級 |

> **鎵规 1 鐨勬敞鎰忎簨椤?*
> 1. 1.4 闇€鍦?**8 涓湇鍔?*鍚屾椂钀藉湴锛沗platform-order` 宸插湪骞跺彂鏀瑰姩涓柊澧炰簡 `MissingRequestHeaderException` 涓?`ServletRequestBindingException` 涓や釜鍒嗘敮锛?*璇ユ湇鍔″彧宸?404/405/415 涓変釜**锛堣 搂0.2锛夈€?> 2. 1.10 / 1.13 / 1.14 浼氭敼鍔ㄩ噾棰濆睍绀猴紝鏀瑰悗蹇呴』璺?`npm test`锛堢幇鏈?`format.test.js` 鏄枃妗堥攣锛屼細鎸′綇涓嶅皬蹇冪殑鏍煎紡婕傜Щ锛夈€?> 3. 鎵€鏈夊墠绔鍙峰湪骞跺彂鏀瑰姩缁撴潫鍓嶉兘涓嶅彲淇★紝**鍔ㄦ墜鍓嶅繀椤婚噸鏂?`grep`**锛埪?.3锛夈€?
### 鎵规 1.5 鈥?琛屼负鍙樻洿绫伙紙闇€浜у搧/鍚庣纭鍚庡啀鍔紝涓嶈娣疯繘鎵规 1锛?
| # | 鍐呭 | 涓轰粈涔堜笉鑳借嚜鍔ㄦ敼 |
|---|---|---|
| 1.5.1 | `common-payment-channel` 鐨?4 澶勬敮浠樺洖璋冮獙绛鹃潤榛樺け璐ワ紙`WechatChannel.java:166,305`銆乣AlipayChannel.java:123`銆乣StripeChannel.java:115`锛夎ˉ鏃ュ織 + 鍛婅 | 琛ユ棩蹇楁湰韬畨鍏紝浣嗐€岄獙绛惧け璐ユ槸鍚﹀簲鍛婅/闄愭祦/钀藉簱瀹¤銆嶆槸涓氬姟鍐崇瓥锛涗笖璇ユ湇鍔?*褰撳墠闆?logger**锛屽姞鏃ュ織闇€鍏堢‘璁ゆ棩蹇楄劚鏁忓彛寰?|
| 1.5.2 | `CollectApplicationService.java:349` 閫€娆捐ˉ鍋垮け璐ャ€乣ResourceStateClient.java:130` 寮€鍙板崰鐢ㄩ檷绾?| 浜岃€呭綋鍓嶆槸**鏈夋剰鐨勯檷绾ф斁琛?*锛堟敞閲婅嚜杩般€岄鏋跺拷鐣ャ€嶃€屾湇鍔′笉鍙揪鍒欓檷绾ф斁琛屻€嶏級锛涜鏀规垚澶辫触鍏抽棴杩樻槸淇濈暀闄嶇骇鏀捐 + 鍛婅锛屽睘浜у搧/璧勯噾鍙ｅ緞鍐崇瓥 |
| 1.5.3 | `SmsPasswordResetSender.java:56` + `PasswordRecoveryApplicationService.java:81` 鐨勩€屽彂閫佸け璐ヤ粛 200銆?| 鏀规垚 5xx/鏄庣‘澶辫触浼氭敼鍙樺墠绔綋楠岋紝闇€涓庝骇鍝佺‘璁わ紙涔熷彲鑳戒骇鍝佸氨鏄銆屼笉鏆撮湶璐﹀彿鏄惁瀛樺湪銆嶁€斺€旈偅搴旀敼鎴愰潤榛樻垚鍔熶絾**蹇呴』鍛婅**锛?|
| 1.5.4 | `c-end/app.js:314-316` 鐨?`scheduleAt` 鏃跺尯淇 | 闇€鍏堢‘璁ゅ悗绔寜 UTC 杩樻槸鏈湴瑙ｉ噴锛涙敼閿欎細璁╂墍鏈夐绾﹀亸 8 灏忔椂 |
| 1.5.5 | `sdk/common/.../AesGcmCipher.java:32`銆乣order/.../ResourceStateClient.java:38` 鐨勭‖缂栫爜瀵嗛挜榛樿鍊?| 灞為厤缃?瀵嗛挜娌荤悊锛屼笖 `validate-secret-exposure.ps1` 褰撳墠涓嶆壂 `.java`锛堣 6.5 鏈熬锛夛紝寤鸿鍙︾珛涓撻」 |

### 鎵规 2 鈥?闇€瑕佷骇鍝?鏈纭锛堝缓璁笌浜у搧杩囦竴杞湳璇〃鍚庣粺涓€鏀癸級

| # | 闇€纭椤?| 鍊欓€?|
|---|---|---|
| 2.1 | 銆屽寘鍘?/ 璧勬簮銆嶅澶栫敤鍝釜锛坄router/index.js:71` 褰撳墠鍐?`鍖呭帰/璧勬簮`锛?| 鐢ㄦ埛闈€屽寘鍘€嶏紝浠ｇ爜/JSDoc 淇濈暀 resource |
| 2.2 | B 绔О鍛硷細銆屽晢鎴风銆嶈繕鏄€岃繍钀ョ銆嶏紙`b-end/App.vue:5` 鐜颁负銆孉380 鍟嗘埛宸ヤ綔鍙般€嶏級 | 涓?admin 鐨勩€岀鎴枫€嶅叧绯婚渶涓€骞跺畾涔?|
| 2.3 | 鍚屼竴閲戦锛氥€屽簲鏀躲€嶈繕鏄€屽緟鏀躲€嶏紙`b-end/Orders.vue:58`锛?| 寤鸿銆屽簲鏀躲€?|
| 2.4 | 銆屽晢瀹?/ 鍟嗘埛 / 闂ㄥ簵 / 绉熸埛銆嶅洓閫夊嚑銆佸垎鍒搴斿摢涓€灞?| 寤鸿锛氱鎴凤紙骞冲彴锛夆啋 闂ㄥ簵锛堢粡钀ヤ富浣擄級鈫?鍟嗘埛绔紙B 绔?App锛夛紱鍒犻櫎銆屽晢瀹躲€?|
| 2.5 | 鍌ㄥ€煎搧鐗屽悕鏈€缁堝舰鎬侊紙鏄惁灏卞彨 A380甯侊紝涓旀槸鍚﹀厑璁哥鎴锋敼鍚嶏級 | 鑻ュ厑璁?鈫?鍓嶇涓嶅緱鏈夐粯璁ゅ€?|
| 2.6 | **澶氳瑷€鏄惁鍦?2.0 鑼冨洿**锛坄platform/tenants.vue:30-31` 宸叉湁銆岄粯璁よ瑷€/榛樿鏃跺尯銆嶉厤缃」锛?| 鏄?鈫?寮曞叆 `vue-i18n` + 鎶?key 鏄嫭绔嬪ぇ宸ョ▼锛涘惁 鈫?鏄庣‘鏍囨敞涓洪鐣欏苟缁熶竴涓枃瑙勮寖 |
| 2.7 | 閿欒鐮佺粺涓€锛氫笂涓嬫枃绫?5 鐮佹敹鏁涗负 `TENANT_CONTEXT_REQUIRED` + 401 鏄惁鍙帴鍙?| 褰卞搷鍓嶇鏄犲皠涓庢枃妗?|
| 2.8 | **銆屽疄鏀躲€嶆槸鍚︾瓑鍚屻€屽凡鏀躲€?* 鈥斺€?`admin/views/tenant/reports.vue:63 '瀹炴敹(鍏?'` 瀵瑰簲瀛楁 `paidAmount`锛岃€?`orders.vue` 鍚屽瓧娈靛彨銆屽凡鏀躲€?| 鑻ュ彛寰勪笉鍚岋紙鐜伴噾瀹炵偣 vs 鍚偍鍊?绉垎锛夛紝蹇呴』鍒嗗紑鍛藉悕涓旀姤琛ㄨ瀵瑰緱涓?|
| 2.9 | **銆屾埧鎬佺湅鏉裤€峷s銆屽寘鍘㈢姸鎬併€?* 鈥斺€?admin 鐢ㄣ€屾埧鎬佸疄鏃剁湅鏉裤€嶏紝`c-end/app.js:261` 鐢ㄣ€屽寘鍘㈢姸鎬併€?| 鍚屼竴鍧?UI 涓ゅ绉板懠 |
| 2.10 | **姝昏瘝姹囨槸鍚︽竻鐞?*锛歚goods` / `sku` / `鍟嗗搧鐩綍` / `鎴垮彴` 鍏ㄤ粨 0 鍛戒腑锛堜絾銆屾埧鎬佺湅鏉裤€嶅湪鐢ㄣ€屾埧銆嶅瓧鏍癸級 | 纭鍚庡姞鍏ョ鐢ㄨ瘝琛?|
| 2.11 | **C 绔?B 绔槸鍚﹀厑璁稿嚭鐜颁换浣曡嫳鏂?* 鈥斺€?`b-end/App.vue:82` 閫忎紶 `e.message`锛屽叾鐢熶骇鑰呮槸鑻辨枃锛坄OAuth state mismatch` / `missing pkce verifier` / `bind failed` / `session not established`锛?| 鍐冲畾鏄€屽叏閮ㄦ槧灏勬垚涓枃銆嶈繕鏄€屼繚鐣欐妧鏈嫳鏂囥€?|
| 2.12 | **`orders.vue` 鎶ヨ〃/鎺ュ彛鏃堕棿鏄惁淇濈暀绉?* 鈥斺€?`reports.vue:140 '鏁版嵁鏃剁偣锛氣€lice(0,19)'`銆乣tenants.vue:53` 淇濈暀绉掞紝鍏朵綑 10 澶勪笉淇濈暀 | 瀵硅处鍦烘櫙鍙兘纭疄闇€瑕佺 鈫?搴旂‘璁ゃ€屽摢浜涘瓧娈典繚鐣欑銆嶈€岄潪涓€鍒€鍒?|

### 鎵规 3 鈥?鍚庣涓枃鏂囨涓庣爜琛ㄦ不鐞嗭紙鍙湪鎵规 1 鍚庡苟琛岋級

| # | 鍐呭 |
|---|---|
| 3.1 | SaaS 闈?54 鏉¤嫳鏂?message 鎵归噺鏀逛腑鏂囷紙4.2 琛紱im-* 112+55+47 鏉″彲鍙︾珛 IM 涓撻」锛岃嫢 IM 瀹㈡埛绔‘涓虹函鎶€鏈潰鍒欐敼鐢?`code` 涓轰富銆乵essage 鍙负鑻辨枃浣嗛渶浜у搧纭锛?|
| 3.2 | 涓婁笅鏂囩被 5 鐮佹敹鏁涳紙4.1 琛級 |
| 3.3 | 淇 `CollectApplicationService.java:202` 鐨勭爜/娑堟伅鐭涚浘 |
| 3.4 | 浠庡悗绔鍑洪敊璇爜娓呭崟锛圤penAPI/甯搁噺绫伙級椹卞姩 `open-saas-admin` 鐨?`ERROR_CODE_MESSAGES`锛屾秷闄?120 涓湭鏄犲皠鐮?|
| 3.5 | 鏂板闂ㄧ `scripts/validate/validate-exception-handling.ps1`锛氭牎楠屾瘡涓彲閮ㄧ讲鏈嶅姟瀛樺湪 `GlobalExceptionHandler` 涓斿寘鍚?`Exception` 鍏滃簳 + `log.error(..., ex)` + `code` 瀛楁锛涙牎楠屽澶?message 涓嶅惈 ASCII-only 鏂囨锛堢櫧鍚嶅崟鏈哄埗锛夛紱鐧昏鍒?`invoke-engineering-validation.ps1` 鐨?`$validators`锛堝綋鍓?16 涓剼鏈?*娌℃湁涓€涓?*瑕嗙洊寮傚父鍑哄彛锛?|

### 鍙墽琛岄獙璇佸懡浠?
**A. 浠撳簱鏃㈡湁闂ㄧ锛堝彧璺戜笌寮傚父/鏃ュ織鐩稿叧鐨?3 涓瓙鑴氭湰锛岀绾э級**

```powershell
cd D:\projects\cnb\open-im-server
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\validate-java-logging-conventions.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\validate-java-exception-logging.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\validate-secret-exposure.ps1
```
> 褰撳墠棰勬湡锛氬墠涓や釜 **pass**锛堟湰娆″璁＄‘璁?0 鍛戒腑锛夛紱`secret-exposure` 涓庢湰娆″紓甯?鏂囨闂鏃犲叧锛屽彲璺宠繃銆?> 鍏ㄩ噺闂ㄧ锛坄-Scope Full` 浼氭墽琛?`mvnw clean verify`锛?*鑰楁椂寰堥暱**锛屼笉寤鸿鍦ㄥ璁￠樁娈佃窇锛夛細
> ```powershell
> powershell -File .\scripts\validate\invoke-engineering-validation.ps1 -Scope Full
> ```

**B. 涓€閿洖褰掋€岀粺涓€閿欒浣撱€嶆帰閽堬紙鍦ㄩ泦缇ゅ唴鎵ц锛岄伩鍏嶄緷璧?NodePort锛?*

```powershell
# 鎶?18 涓湇鍔′竴娆℃€ф墦瀹岋細鏈熸湜 404/405/415 鍧囦负 {code,message}锛屼笖涓嶅啀鍑虹幇 500
$script = @'
for hp in platform-admin-service:4150 platform-customer-service:4160 platform-identity-service:4100 \
          platform-marketing-service:4170 platform-order-service:4130 platform-resource-service:4120 \
          platform-tenant-service:4110 common-audit-service:4190 common-mail-service:4210 \
          common-media-service:3600 common-payment-service:4140 common-payment-channel-service:4180 \
          common-sms-service:4200 im-admin-service:3400 im-conversation-service:3300 \
          im-message-service:3200 im-user-service:3100 group-idaas-service:4220
do
  host=${hp%%:*}; port=${hp##*:}
  for probe in "-X PATCH|/" "-X POST -H Content-Type:text/plain -d x|/" "|/zzz-nonexistent"
  do
    opts=${probe%%|*}; path=${probe##*|}
    code=$(curl -s -o /tmp/b -w "%{http_code}" --max-time 8 $opts "http://$host:$port$path")
    echo "$host $path -> $code $(head -c 120 /tmp/b | tr -d '\n')"
  done
done
'@
$script | kubectl -n gv-im-local exec -i saas-admin-84d6db87df-w6rgp -- sh -s
```
> **褰撳墠鍩虹嚎锛堜慨澶嶅墠锛?*锛歚platform-order` / `platform-resource` / `platform-tenant` 涓夎鍏ㄦ槸 `500`锛? 涓湇鍔℃槸 Spring 榛樿浣擄紱`group-idaas` 鏄?HTML銆?
**C. 蹇呭～璇锋眰澶存槸鍚﹁蛋缁熶竴浣?*

```powershell
$d = "$env:TEMP\cbn_audit"; New-Item -ItemType Directory -Force $d | Out-Null
# 鐧诲綍鎷夸細璇?'{"username":"admin","password":"芦宸茶疆鎹?绠＄悊鍛樺垵濮嬪彛浠ぢ?,"ttlHours":1}' |
  Set-Content "$d\login.json" -Encoding ASCII -NoNewline
curl.exe -s -c "$d\ck.txt" -X POST http://192.168.31.91:30081/api/v1/admin/auth/login `
  -H "Content-Type: application/json" -d "@$d\login.json"
# 鍙?csrf + 閫変笂涓嬫枃
$csrf = (curl.exe -s -b "$d\ck.txt" http://192.168.31.91:30081/api/v1/admin/auth/csrf | ConvertFrom-Json).csrfToken
'{"contextId":"100:100:100"}' | Set-Content "$d\ctx.json" -Encoding ASCII -NoNewline
curl.exe -s -b "$d\ck.txt" -X POST http://192.168.31.91:30081/api/v1/admin/context/select `
  -H "Content-Type: application/json" -H "X-CSRF-Token: $csrf" -d "@$d\ctx.json"
# 瀵圭収鎺㈤拡锛氬ご缂哄け vs 澶翠负绌?'{"customerId":1,"amount":100,"currency":"CNY","paymentMethod":"CASH"}' |
  Set-Content "$d\w.json" -Encoding ASCII -NoNewline
curl.exe -s -i -b "$d\ck.txt" -X POST http://192.168.31.91:30002/api/v1/admin/wallets/recharge -H "Content-Type: application/json" -d "@$d\w.json"
curl.exe -s -i -b "$d\ck.txt" -X POST http://192.168.31.91:30002/api/v1/admin/wallets/recharge -H "Content-Type: application/json" -H "Idempotency-Key;" -d "@$d\w.json"
```
> **鏈熸湜淇鍚?*锛氫袱鏉￠兘杩斿洖 `{"code":"IDEMPOTENCY_KEY_REQUIRED","message":"缂哄皯 Idempotency-Key"}`銆?> **褰撳墠鍩虹嚎**锛氱 1 鏉℃槸 `{"timestamp",...}` 榛樿浣擄紝绗?2 鏉℃槸缁熶竴浣撱€?
**D. 鐧诲綍澶辫触鐘舵€佺爜**

```powershell
'{"loginType":"username","loginIdentifier":"admin","credential":"芦宸茶疆鎹?绠＄悊鍛樺垵濮嬪彛浠ぢ?}' |
  Set-Content "$env:TEMP\cbn_audit\idlogin.json" -Encoding ASCII -NoNewline
curl.exe -s -i -X POST http://192.168.31.91:30081/api/v1/identity/login `
  -H "Content-Type: application/json" -d "@$env:TEMP\cbn_audit\idlogin.json"
```
> **鏈熸湜淇鍚?*锛歚401 {"code":"AUTH_INVALID_CREDENTIAL","message":"鐢ㄦ埛鍚嶆垨瀵嗙爜閿欒"}`锛堥敊璇嚟鎹級锛涘綋鍓嶅熀绾挎槸 **500 榛樿浣?*銆?
**E. 銆?00 鏄惁璁版棩蹇椼€嶅洖褰?*

```powershell
# 瑙﹀彂涓€娆″叕寮€璺敱 500锛岀劧鍚庣湅鏃ュ織锛堜慨澶嶅悗搴旇兘鐪嬪埌 ERROR 琛岋級
curl.exe -s -o NUL -X POST http://192.168.31.91:30002/client/release-check `
  -H "Content-Type: application/json" -d "{bad"
kubectl -n gv-im-local logs im-admin-service-77596c844b-2b942 --since=2m | Select-String "ERROR"
```
> **褰撳墠鍩虹嚎**锛氭棤浠讳綍杈撳嚭銆?
**F. 鍓嶇妫€鏌?*

```powershell
cd D:\projects\cnb\open-saas-admin; npm test; npm run build
cd D:\projects\cnb\open-saas-mobile; npm test          # 浠撳簱鏍瑰嵆鏈?test 鑴氭湰
# 閲戦/鏃堕棿鏍煎紡鍥炲綊锛堝簲鍙墿 utils/format.js 涓€涓疄鐜帮級
Select-String -Path 'src\**\*.vue','src\**\*.ts' -Pattern "/ 100|\* 100"
# 纭紪鐮佸搧鐗屽悕鍥炲綊锛堝簲涓?0锛?Select-String -Path 'src\**\*.vue','src\**\*.ts' -Pattern "A380甯亅鍌ㄥ€煎竵"
```

**F2. 鏈鏂板彂鐜扮殑鍓嶇纭畾鎬х己闄凤紙淇鍓嶅悗閮借兘璺戯紝鐢ㄦ潵纭锛?*

```powershell
$admin = 'D:\projects\cnb\open-saas-admin'
$mobile = 'D:\projects\cnb\open-saas-mobile'

# F2.1 鍙岃揣甯佺鍙凤細鏈熸湜淇鍚?0 鍛戒腑
Select-String -Path "$admin\src\views\**\*.vue" -Pattern '楼\s*\{\{\s*(fmtCents|formatYuan|formatYuanValue|fmtCentsCompact)\('
# 褰撳墠鍛戒腑锛歰rders.vue:410

# F2.2 鏈湴閲嶅澹版槑鐨?formatTime锛堟湡鏈?0 鍛戒腑锛涗粎 utils/format.js 搴旀湁瀹炵幇锛?Select-String -Path "$admin\src" -Include *.vue,*.js -Pattern '^(export )?function formatTime' -Recurse |
  ForEach-Object { "{0}:{1}" -f $_.Path,$_.LineNumber }

# F2.3 C 绔噾棰濆昂搴︼紙浜哄伐鏍稿锛?53 搴旀敼涓?minorMoney锛?Select-String -Path "$mobile\c-end\app.js" -Pattern 'money\(it\.totalAmount|function money|function minorMoney|fenToYuanStr'
# 鏈熸湜淇鍚庯細853 琛屼笉鍐嶅嚭鐜?money(it.totalAmount锛沠enToYuanStr 宸插垹闄?
# F2.4 C 绔綑棰濇槸鍚﹂櫎 100锛堟湡鏈涗慨澶嶅悗杩欎簺琛岄兘鍑虹幇 /100 鎴栨敼鐢ㄧ粺涓€鍑芥暟锛?Select-String -Path "$mobile\c-end\app.js" -Pattern 'availableAmount \|\| 0\)\.toLocaleString'

# F2.5 C 绔?UTC 鏃ユ湡锛堟湡鏈涗慨澶嶅悗 0 鍛戒腑锛?Select-String -Path "$mobile\c-end\app.js" -Pattern 'toISOString\(\)\.slice\(0,\s*10\)'

# F2.6 瑁告灇涓?/ 鑻辨枃涓嬫媺锛堟湡鏈涗慨澶嶅悗 0 鍛戒腑锛?Select-String -Path "$admin\src\views\platform\iam\roles.vue" -Pattern 'label="(PLATFORM|TENANT)"'
Select-String -Path "$admin\src\views\tenant\ktv-config.vue" -Pattern 'HOUR|HALF_HOUR|FLOOR_BLOCK|ROUND_UP|CONSUMER_FAVOR'
Select-String -Path "$mobile\c-end\oauth.js" -Pattern 'IM login session has expired|IM bridge authorization failed'

# F2.7 閲嶅鏂囨甯搁噺锛堟湡鏈涗慨澶嶅悗鍚勫彧 1 澶勶級
Select-String -Path "$admin\src","$mobile\src","$mobile\c-end" -Include *.vue,*.js -Recurse -Pattern '鏈嶅姟绔湭杩斿洖 CSRF token' |
  ForEach-Object { "{0}:{1}" -f $_.Path,$_.LineNumber }
```

**G. 鍚庣鏁忔劅鏃ュ織 / 闈欓粯鍚炲紓甯稿洖褰掞紙闈欐€侊紝绉掔骇锛?*

```powershell
cd D:\projects\cnb\open-im-server
# G1 鏄庢枃楠岃瘉鐮?/ 閲嶇疆閾炬帴 / 鎵嬫満鍙凤紙鏈熸湜淇鍚?0 鍛戒腑锛?git grep -n -E 'would send reset link to|code=\{\}", mask\(phone\)|to=\{\}.*", phone|tenant.*to=\{\}' -- '*.java'
# 鏇寸簿纭細
Select-String -Path 'im-services\user\im-user-service\src\main\java\com\gvchat\im\user\infra\mail\SmtpPasswordResetMailSender.java' -Pattern 'resetLink|email'
Select-String -Path 'common-services\sms\common-sms-service\src\main\java\com\gvchat\common\sms\infra\provider\*.java' -Pattern 'to=\{\}'
# G2 瀹屽叏绌虹殑 catch锛堟湡鏈涗慨澶嶅悗鍔犳敞閲?鏃ュ織锛?git grep -n -E 'catch\s*\(\s*\w+\s+ignored\s*\)\s*\{\s*\}' -- '*.java'
# G3 涓㈠純 exception 褰㈠弬鐨勮緟鍔╂柟娉?Select-String -Path 'common-services\media\common-media-service\src\main\java\com\gvchat\common\media\media\*.java' -Pattern 'unavailable\(String'
# G4 鍏滃簳鍒嗘敮鏄惁璁板爢鏍堬紙鏈熸湜 8 涓湇鍔￠兘鏈?log.error 涓斾紶 ex锛?Get-ChildItem -Recurse -Filter GlobalExceptionHandler.java | Where-Object { $_.FullName -notmatch '\\target\\' } |
  ForEach-Object { $t=Get-Content $_.FullName -Raw; "{0,-22} generic={1,-4} logs={2}" -f `
    ($_.FullName -split '\\java\\')[0].Split('\')[-1], `
    ($t -match '@ExceptionHandler\(Exception\.class\)'), ($t -match 'log\.error') }
```


---

## 6. 鍚庣鏃ュ織瑙勮寖涓庛€屽悶寮傚父銆嶏紙瀵瑰簲浠诲姟 1.4锛岄潤鎬佸叏閲忔壂鎻忥級

### 6.1 闂ㄧ瀹為檯瑙勫垯锛堝厛鐪嬮棬绂侊紝鍐嶇湅鐜板疄锛?
| 鑴氭湰 | 瑙勫垯 | 鑼冨洿 | 褰撳墠缁撴灉 |
|---|---|---|---|
| `scripts/validate/validate-java-logging-conventions.ps1`锛?1 琛岋紝**鍙湁 1 鏉¤鍒?*锛?| 绗?8琛?`-match 'LoggerFactory\.getLogger\|import\s+org\.slf4j\.Logger\s*;\|\bLogger\s+\w+\s*='` | `git ls-files '*src/main/java/*.java'` | **pass** |
| `scripts/validate/validate-java-exception-logging.ps1`锛?6 琛岋紝**鍙湁 1 鏉¤鍒?*锛?| 绗?琛?`git grep -n -e 'printStackTrace(' -- '*.java'` | 鍏ㄩ儴宸茶窡韪?`*.java`锛堝惈 test锛?| **pass** |

> **闂ㄧ鍏ㄧ豢锛屼絾鏈姤鍛?H-1~H-6銆丮-1~M-8 涓庝笅闈?6.2/6.3 鐨勬墍鏈夐棶棰橀兘涓嶈浠讳綍闂ㄧ瑕嗙洊銆?* `invoke-engineering-validation.ps1` 鐨?16 涓剼鏈噷娌℃湁涓€涓鏌ャ€岀粺涓€寮傚父鍑哄彛鏄惁瀛樺湪銆嶃€?00 鏄惁璁板爢鏍堛€嶃€宮essage 鏄惁涓轰腑鏂囥€嶃€傝繖鏄湰娆″璁℃渶鍊煎緱涓婃姤鐨?*娴佺▼鎬х粨璁?*銆?
### 6.2 鏃ュ織瀹炵幇鏈韩锛氬凡缁熶竴锛堢粨璁猴細鍚堣锛?
| 妫€鏌ラ」 | main | test | 闂ㄧ瑕嗙洊 |
|---|---|---|---|
| `LoggerFactory.getLogger` | **0** | **0** | 鉁?瑙勫垯1 |
| `import org.slf4j.Logger;` | **0** | **0** | 鉁?瑙勫垯2 |
| `Logger xxx =` | **0** | **0** | 鉁?瑙勫垯3 |
| `LogManager.getLogger` / `java.util.logging` | **0** | **0** | 鉂?鐩插尯 |
| `System.out.*` / `System.err.*` | **0** | **0** | 鉂?闂ㄧ涓嶆鏌?|
| `printStackTrace(` | **0** | **0** | 鉁?鍙︿竴鑴氭湰 |
| `@Log4j2` / `@Log` / `@CommonsLog` | **0** | **0** | 鉂?鐩插尯 |

- **106 涓被**甯?`@Slf4j`锛?*365 澶?* `log.*` 璋冪敤鍒嗗竷鍦?**104 涓被**锛?*鍏ㄩ儴**宸?`@Slf4j`锛堣繚瑙?0锛夈€? 涓被娉ㄨВ浜嗕絾鏃犳棩蹇楄皟鐢紙鍐椾綑锛屼綆锛夈€?- **缁撹**锛氬弬鑰?6.1锛屼袱涓?java 鏃ュ織闂ㄧ褰撳墠閮芥槸缁跨殑銆?
### 6.3 鏁忔劅淇℃伅杩涘叆鏃ュ織锛堟寜涓ラ噸搴︼級

| # | 鏈嶅姟 | 鏂囦欢:琛?| 鐜扮姸 | 褰卞搷 | 涓ラ噸搴?|
|---|---|---|---|---|---|
| 1 | im-user | `im-services/user/im-user-service/src/main/java/io/openware/im/user/infra/mail/SmtpPasswordResetMailSender.java:37` | `log.info("[password-reset] mail disabled, would send reset link to {}: {}", email, resetLink)` | **瀵嗙爜閲嶇疆閾炬帴锛堝惈涓€娆℃€?token锛? 閭鏄庢枃**锛涙棩蹇楀彲璇诲嵆鍙帴绠¤处鍙枫€俙im.mail.enabled` 榛樿 false 鈫?**杩欐槸榛樿鍒嗘敮** | 楂?|
| 2 | im-user | `.../infra/sms/SmsPasswordResetSender.java:37` | `... code={}", mask(phone), code)` | **鐭俊楠岃瘉鐮佹槑鏂?*锛堟墜鏈哄彿宸茶劚鏁忋€侀獙璇佺爜鏈劚鏁忥級銆俙im.sms.enabled` 榛樿 false 鈫?榛樿鍒嗘敮 | 楂?|
| 3 | common-sms | `common-services/sms/.../infra/provider/TencentSmsProvider.java:64,101,104` | `... to={} ...", phone` | **瀹屾暣鎵嬫満鍙凤紙PII锛?*锛涘悓妯″潡 `SmsApplicationService.java:34-35` 宸插湪鐢?`PhonePrivacy.digest/mask`锛宲rovider 灞傛湭鑴辨晱 | 楂?|
| 4 | common-sms | `.../infra/provider/AliyunSmsProvider.java:54,87,90` | 鍚屼笂 | 鍚屼笂 | 楂?|
| 5 | common-mail | `common-services/mail/.../infra/provider/SmtpMailProvider.java:46,56,58` | `... to={} subject={}", to` | 鏀朵欢浜洪偖绠?PII | 涓?|
| 6 | im-user | `.../infra/mail/SmtpPasswordResetMailSender.java:47,49` | `... reset email to {}", email` | 閭 PII | 涓?|
| 7 | payment-channel | `common-services/payment-channel/.../infra/provider/WechatChannel.java:142,259`锛沗StripeChannel.java:83` | `... String.valueOf(e.getMessage())` 浣滀负**杩斿洖鍊煎瓧娈?* | 搴曞眰寮傚父 message 杩涘叆鍝嶅簲浣擄紝鍙兘娉勯湶缃戝叧/璇佷功閰嶇疆绾跨储锛涙棤鏃ュ織鏃犲爢鏍?| 涓?|
| 8 | idaas | `group-services/idaas/.../application/AuthApplicationService.java:46,53` | `... username={}", request.username()` | 鐢ㄦ埛鍚?PII锛屽彲鐢ㄤ簬璐﹀彿鏋氫妇 | 浣?|

**鏄庣‘鏍稿涓哄畨鍏紙鏈彂鐜版硠闇诧級**锛歚JwtAuthenticationFilter.java:100,104`锛堝彧璁?token expired/invalid + method/uri锛屼笉鎵撳嵃 token 鏈綋锛夈€乣InternalServiceAuthenticationFilter.java:61`锛堝彧璁板竷灏旓級銆乣OpenPlatformApplicationService.java:336,382`锛圤Auth token 杞崲鍙 id锛夈€乣RedisOauthTokenStore.java:224`锛堝彧璁?type锛夈€?
### 6.4 寮傚父鏃ュ織涓㈠け鍫嗘爤 / 缂哄畾浣嶄笂涓嬫枃

| 鏈嶅姟 | 鏂囦欢:琛?| 鐜扮姸 | 涓ラ噸搴?|
|---|---|---|---|
| sdk | `sdk/infrastructure/.../audit/AuditClient.java:75-76` | `log.warn("寮傛鍐欏璁″け璐? action={}... cause={}", ..., e.getMessage())` 鈫?**鏃犲爢鏍?*涓?`return null` **鍚炴帀**瀹¤鍐欏け璐?| 楂?|
| im-message | `.../infra/projection/MessageProjectionService.java:43`銆乣:105`銆乣:121` | 鍧囦负 `ex.getMessage()` / `ex.toString()`锛?*鏃犲爢鏍?* | 涓?|
| im-message | `.../infra/integration/admin/AdminFeatureToggleAdapter.java:55` | `log.warn("Failed to fetch feature flags, keep last cache")` 鈥斺€?**鏃㈡棤寮傚父涔熸棤 id**锛屽紑鍏充笉鐢熸晥鏃犵嚎绱?| 涓?|
| im-message | `.../infra/integration/admin/AdminModerationWordSource.java:52` | 鍚屼笂锛堝彧璁扮紦瀛樻潯鏁帮級 | 涓?|
| im-message | `.../scheduler/SecretMessageDestroyScheduler.java:23`銆乣SecretGroupMessageDestroyScheduler.java:23` | `log.warn("... scan failed", e)` 鈥斺€?鎵瑰鐞嗗け璐?*鏃犳壒娆?鏃堕棿绐?鏉℃暟** | 涓?|
| im-user | `.../infra/scheduler/SelfDestructSweepJob.java:27`銆乣AccountCancellationSweepJob.java:35` | 鍚屼笂 | 涓?|
| im-message / im-conversation | `.../infra/integration/*Adapter.java`锛坄UserServiceProfileAdapter.java:35`銆乣UserServiceDeviceKeyAdapter.java:41`锛?| 鏃?groupId/userId | 涓?|
| platform-* | `tenant/.../handler/GlobalExceptionHandler.java:58,87`锛沗resource/.../:57,86`锛沗order/.../:43` | `log.error("Unhandled xxx-service exception", ex)` 鈥斺€?**鏃?uri/璇锋眰 id/traceId**锛圚-3 涔嬪鐨勬瑕侀」锛涜繖 3 涓湇鍔¤嚦灏戞湁鍫嗘爤锛?| 涓?|

> 鍙︽湁 **21 缁?/ 42 澶?* `Failed to start/close X consumer/listener` 妯℃澘鏃ュ織锛堝悇 `*EventConsumer` / `*EventListener`锛夛紝鍧囨棤 id/code 浣嗛兘甯﹀爢鏍?鈥斺€?灞炲櫔闊?寮卞畾浣嶏紝鍙帴鍙楋紝寤鸿琛?topic/group銆?
### 6.5 鍚炲紓甯?/ 鍑洪敊鍚庤繑鍥炴垚鍔燂紙Top 10锛屼笌鏈 H-1~H-6 浜掕ˉ锛?
| # | 鏈嶅姟 | 鏂囦欢:琛?| 鐜扮姸 | 褰卞搷 | 涓ラ噸搴?|
|---|---|---|---|---|---|
| 1 | payment-channel | `common-services/payment-channel/.../infra/provider/WechatChannel.java:166-168`銆乣:305-307`锛沗AlipayChannel.java:123-125`锛沗StripeChannel.java:115-117` | 鍥炶皟楠岀 `catch (Exception e) { return fail(request); }` 鈥斺€?**璇ユ湇鍔?24 澶勫娉?catch 鍏ㄩ儴鏃犳棩蹇楋紝鏁翠釜 `common-payment-channel` 娌℃湁 logger** | 鏀粯鍥炶皟楠岀寮傚父琚潤榛樿浆鎴愩€岀鍚嶅け璐ャ€嶏紝鏃犱换浣曠棔杩?| 楂?|
| 2 | payment | `common-services/payment/.../application/CollectApplicationService.java:349-351` | `catch (RuntimeException ignored) {}` + 娉ㄩ噴`// 琛ュ伩澶辫触锛氱湡瀹炲疄鐜板啓 Outbox/鍛婅閲嶈瘯锛岄鏋跺拷鐣 | **绉垎/鍌ㄥ€奸€€娆捐ˉ鍋垮け璐ヨ鍚?*锛岃祫閲戜晶闈欓粯涓㈠け | 楂?|
| 3 | order | `platform-services/order/.../infra/client/ResourceStateClient.java:130-132` + `.../application/KtvSessionApplicationService.java:110-113` | `occupy` 澶辫触闈欓粯 `return Optional.empty()`锛岃皟鐢ㄦ柟 `.ifPresent(...)` 璺宠繃鍗犵敤鍚庣户缁?`setStatus("OPEN")` | 璧勬簮鏈嶅姟鏁呴殰鏈熼棿**寮€鍙版垚鍔熶絾鏃犲崰鐢ㄨ褰?*锛屽彲閲嶅寮€鍙?閲嶅璁¤垂 | 楂?|
| 4 | admin | `platform-services/admin/.../application/StaffAccountApplicationService.java:154-155`銆乣:165-166` | `catch (RuntimeException ignored) {}` 鈥斺€?**瀹屽叏绌轰綋** | 瑙掕壊缁戝畾鍥炴粴琛ュ伩澶辫触鏃犵棔 | 楂?|
| 5 | media | `common-services/media/.../media/MinioMediaStorage.java:267-269`銆乣OssMediaStorage.java:164` | 杈呭姪鏂规硶 `unavailable(String, Exception)` **涓㈠純 exception 褰㈠弬** 鈫?璇ヤ袱鏂囦欢 **26 澶?* catch 鍏ㄩ儴涓㈠け鏍瑰洜锛沗MinioMediaStorage.java:106,120` 杩樻妸鍩虹璁炬柦鏁呴殰鎶ユ垚 400 | 瀛樺偍鏁呴殰涓嶅彲璇婃柇 | 楂?|
| 6 | im-user | `.../infra/sms/SmsPasswordResetSender.java:56-58` + `.../application/account/PasswordRecoveryApplicationService.java:80-81` | sender 鍚炴帀鍙戦€佸け璐ワ紱璋冪敤鏂归殢鍚?`log.info("[password-reset] issued reset sms code ...")` 骞?**200 杩斿洖** | 鐭俊缃戝叧鏁呴殰鏃舵帴鍙ｃ€屾垚鍔熴€嶏紝鐢ㄦ埛姘歌繙鏀朵笉鍒扮爜 | 楂?|
| 7 | common-sms | `common-services/sms/.../application/service/SmsApplicationService.java:50-59` | `catch (RuntimeException ex) { log.setStatus("FAILED"); }` 鈥斺€?**鏃犱换浣曟棩蹇?* | 鐭俊涓嶅彂鏃犱汉鍙煡 | 涓?|
| 8 | common-mail | `common-services/mail/.../application/service/MailApplicationService.java:44-49` | 鍚屼笂 | 閭欢涓嶅彂鏃犱汉鍙煡 | 涓?|
| 9 | tenant | `platform-services/tenant/.../infra/authorization/PermissionSnapshotCache.java:38,55,61,69,77` | 5 澶勭┖ catch锛坄/* redis 涓嶅彲鐢ㄦ椂渚濊禆鏈湴缂撳瓨 */` 绛夛級 | Redis 鏁呴殰鏃舵潈闄愬揩鐓ч潤榛橀檷绾?| 涓?|
| 10 | gateway | `gateways/gateway/.../security/SaasSessionAuthenticationFilter.java:184-186` | `catch (Exception ignored) { return Optional.empty(); }` 鈫?涓婂眰 `:87` 鎶?`SAAS_CONTEXT_REQUIRED` | 浼氳瘽鏁版嵁鎹熷潖琚鎶ユ垚銆岀己涓婁笅鏂囥€嶏紝鐪熷疄鍘熷洜鏃犳棩蹇?| 涓?|

**瀹芥硾 catch 鍩烘暟**锛歚catch (Exception|Throwable|RuntimeException)` 鍏ㄤ粨 **287 澶?*锛坄RuntimeException` 60锛宍Throwable` 0锛夈€傛寜鏈嶅姟锛歩m-message 52銆乮m-access-ws 51銆乸latform-admin 34銆乧ommon-media 30銆乧ommon-payment-channel 24銆乮m-user 18銆乧ommon-payment 15銆乸latform-order 14銆乸latform-identity 9銆乻dk 8銆乮m-admin 7銆乸latform-tenant 6銆乧ommon-sms 4銆乸latform-marketing 4銆乮m-conversation 4銆乸latform-resource 3銆乧ommon-mail 2銆乬ateway 1銆乬roup-idaas 1銆?*platform-customer 0銆乧ommon-audit 0**锛堣繖涓や釜鏈嶅姟鏃㈡棤 catch 涔熸棤浠讳綍鏃ュ織锛夈€?
**鐩搁偦鍙戠幇锛堣秴鍑哄紓甯歌寖鍥达紝浠呮爣娉級**锛歚validate-secret-exposure.ps1:13` 鐨?`$candidateExtensions` 涓嶅惈 `.java`锛屽洜姝?**Java 鍐呯‖缂栫爜瀵嗛挜榛樿鍊奸€冭繃璇ラ棬绂?*锛屼緥濡?`sdk/common/.../crypto/AesGcmCipher.java:32`锛坄@Value("${app.crypto.aes-secret:gv-aes-dev-secret-change-me}")`锛夈€乣platform-services/order/.../infra/client/ResourceStateClient.java:38`锛坄...:gv-im-internal-dev-secret`锛夈€傚缓璁彟娲句竴涓厤缃潰瀹¤銆?
---

## 7. 鍓嶇琛ュ厖鍙戠幇锛堥€愭枃浠舵牳瀵癸紝鍚‘瀹氭€х己闄凤級

### 7.1 纭畾鎬х己闄凤紙鍙洿鎺ヤ慨锛岄潪椋庢牸闂锛?
| # | 浣嶇疆锛堣鍙烽殢骞跺彂鏀瑰姩婕傜Щ锛岃閲嶆柊 grep锛?| 鐜扮姸 | 褰卞搷 |
|---|---|---|---|
| 1 | `open-saas-admin/src/views/tenant/orders.vue:410` | `楼 {{ fmtCents(payableMinor) }}` + `:818 const fmtCents = formatYuan`锛坄formatYuan` 杩斿洖 `'楼 ' + ...`锛?| 娓叉煋 **"楼 楼 123.45"**銆?*宸叉寜褰撳墠鏂囦欢鏍搁獙浠嶅瓨鍦?*锛坢d5 `9B2614AAA014EBDB5F14AE8BD9AFC373`锛?|
| 2 | `open-saas-mobile/c-end/app.js:853` vs `:809` | `'閲戦锛? + money(it.totalAmount)`锛坄money` **涓嶉櫎 100**锛塿s `minorMoney(o.totalAmount, ...)`锛堥櫎 100锛?| 鍚屼竴 DTO 鍚屼竴瀛楁涓ょ灏哄害锛孡853 **鏄剧ず涓哄疄闄呭€肩殑 100 鍊?* |
| 3 | `open-saas-mobile/c-end/app.js:200,204,222,227,756,760`锛坄:248` 璐︽湰鍚岀悊锛?| `el.textContent = Number(w.availableAmount \|\| 0).toLocaleString('zh-CN')` 鈥斺€?**鏈櫎 100** | 閽卞寘浣欓鏄剧ず涓?*鍒嗙殑鏁板€?*锛堝浣欓 88.80 鍏冩樉绀轰负 `8880`锛夈€傝€?`c-end/saas.js:107` 娉ㄩ噴涓?`:108-111 fenToYuan` 鏄庣‘濂戠害鏄€屽垎 鈫?鍏冦€嶏紱mock 鏁版嵁 `data.js:16 balance: 8880` 鏄?*鍏冨舰鐘?*锛屾伆濂芥帺鐩栦簡璇?bug |
| 4 | `open-saas-mobile/c-end/app.js:33`锛坄dateOffset`锛変笌 `:655` | `date.toISOString().slice(0,10)` | `toISOString()` 鏄?**UTC**锛沀TC+8 涓嬫湰鍦?00:00鈥?7:59 鏃躲€屼粖澶?N銆嶆爣绛?*鏃╀竴澶?* |
| 5 | `open-saas-mobile/c-end/app.js:314-316` | `new Date(\`${dateStr}T${timeStr}:00\`).toISOString()` | 鎸?*鏈湴**瑙ｆ瀽銆佹寜 **UTC** 搴忓垪鍖?鈫?鎻愪氦鐨勬椂闂存瘮棰勬湡鏃?8 灏忔椂锛堥绾︽椂闂存槸**鍔熻兘鎬?*缂洪櫡锛岄渶鍚庣濂戠害纭锛?|
| 6 | `open-saas-mobile/c-end/app.js:240-242` | `fenToYuanStr(v)` 闄?100 鈥斺€?**0 璋冪敤鐐?*锛堟浠ｇ爜锛?| C 绔洜姝ゆ湁 **3 涓苟瀛?*鐨勯噾棰濇覆鏌撳櫒锛坄money` 涓嶉櫎銆乣minorMoney` 闄?Intl銆佽８ `toLocaleString`锛? 1 涓鍑芥暟 |

### 7.2 纭紪鐮佽嫳鏂?/ 瑁告灇涓捐繘鍏ョ敤鎴风晫闈?
| 浣嶇疆 | 鐜扮姸 | 寤鸿 |
|---|---|---|
| `open-saas-admin/src/views/platform/iam/roles.vue:41,42` | `<el-option label="PLATFORM" .../>` / `label="TENANT"` 鈥斺€?涓嬫媺鐩存帴鏄剧ず鑻辨枃鏋氫妇锛岃€?*鍚屼竴鏋氫妇鍦ㄥ埆澶勫凡缈昏瘧**锛坄views/tenant/staff.vue:161` 鈫?`{ TENANT: '绉熸埛绾?, STORE: '闂ㄥ簵绾?, ORGANIZATION: '缁勭粐绾?, PLATFORM: '骞冲彴绾?, SELF: '鏈汉' }`锛?| 澶嶇敤 `staff.vue` 鐨勬槧灏?|
| `open-saas-admin/src/views/tenant/ktv-config.vue:219,220,232,233,234` | `label="灏忔椂 HOUR"` / `"鍗婂皬鏃?HALF_HOUR"` / `"璁╁埄娑堣垂鑰?CONSUMER_FAVOR"` / `"鍚戜笂鍙栨暣 ROUND_UP"` / `"灏侀《 FLOOR_BLOCK"` 鈥斺€?**5 澶勮８鏋氫妇鎷煎湪涓枃鍚?* | 涓枃鏀?`label`锛屾灇涓炬斁 `value` |
| `open-saas-mobile/c-end/oauth.js:253,255` | `oauthError('IM_SESSION_EXPIRED', 'IM login session has expired')` / `'IM bridge authorization failed'` 鈥斺€?鑻辨枃锛涗絾 `c-end/auth-errors.js:26,31` 宸叉湁瀵瑰簲涓枃 | 鏀逛腑鏂?|
| `open-saas-mobile/src/b-end/App.vue:82` | `errorDescription.value = e?.code ? (e.message \|\| '璇风◢鍚庨噸璇曗€?) : '鈥?` 鈥斺€?**浠讳綍甯?code 鐨勯敊璇兘鐩存帴娓叉煋鍘熷 `e.message`**锛岃€岃繖浜涚敓浜ц€呮槸鑻辨枃锛歚b-end/oauth.js:148 'OAuth state mismatch'`銆乣:170 'missing pkce verifier'`銆乣:178/:236 'bind failed'`銆乣c-end/oauth.js:302/:317 'session not established'` | 鏀硅蛋 code鈫掍腑鏂囨槧灏勶紙瀵归綈 `c-end/auth-errors.js`锛?|
| `open-saas-admin/src/views/platform/tenants.vue:30,31` | `placeholder="zh-CN"` / `"Asia/Shanghai"` | 杈撳叆绀轰緥锛屽彲鎺ュ彈 |

**璐熷悜缁撹锛堥噸瑕侊級**锛氫袱涓粨搴?*娌℃湁浠讳綍** `ElMessage.*('English鈥?)` / `alert('English鈥?)` / `showToast('English鈥?)` / 鑻辨枃琛ㄥご / 鑻辨枃鎸夐挳銆傛墍鏈?sink 鐨勪簨瀹炵敤璇兘鏄畝浣撲腑鏂?鈥斺€?**闂鍦ㄣ€屽叏閲忕‖缂栫爜銆嶄笌銆屽皯閲忚８鏋氫妇/閫忎紶 message銆嶏紝涓嶅湪 sink 灞傜殑涓嫳娣锋帓**銆?
### 7.3 涓嫳娣锋帓 / 闂磋窛涓嶄竴鑷?
- **`A380` / `KTV` / `IM` 鍚庢槸鍚﹀姞绌烘牸涓嶇粺涓€**锛歚'A380绉熸埛'`锛坄b-end/App.vue:6`銆乣c-end/context.test.js:30`锛塿s `'A380 绉熸埛'`锛坄b-end/preview/oauth.js:6`锛夛紱`'KTV 涓氬姟'`锛坄c-end/data.js:4`銆乣c-end/app.js:256`锛塿s `'KTV鍥涘皬鏃舵鍞卞埜'`锛坄c-end/data.js:110`锛夛紱`'闂ㄥ簵ID'`/`'鍛樺伐ID'`锛坄admin/reports.vue:78,80`锛塿s `'瀹㈡埛 ID'`/`'娴佹按 ID'`锛坄admin/ktv-config.vue:124,151,163`锛夈€?- **鍚屼竴瀛楃涓?5 浠芥嫹璐?*锛歚'鏈嶅姟绔湭杩斿洖 CSRF token'` 鍑虹幇鍦?`admin/api/request.js:34`銆乣mobile/src/shared/api/request.js:22`銆乣mobile/src/b-end/oauth.js:72`銆乣c-end/saas.js:46`銆乣c-end/oauth.js:79`銆傚悓鐞?`'褰撳墠椤甸潰涓嶆敮鎸佸畨鍏ㄧ殑 PKCE 鎺堟潈'`锛? 澶勶級銆乣'GVBridge 鏈敞鍏?`锛? 澶勶級銆?- **鍏ㄨ/鍗婅鎷彿娣风敤**锛歚'鍌ㄥ€煎竵(A380甯?'`锛堝崐瑙掞紝`constants/payment-methods.js:4`锛塿s `'2 灏忔椂锛堟帹鑽愶級'`锛堝叏瑙掞紝`login/index.vue:21`锛塿s `'搴旀敹(鍏?'`锛堝崐瑙掞級vs `'鍖呭帰鍗曚环锛堝厓/璁¤垂鍗曚綅锛?`锛堝叏瑙掞紝`ktv-config.vue:23`锛夈€?
### 7.4 鏃堕棿鏍煎紡鍏?11 绉嶅彉浣擄紙绠＄悊绔崟鐙?5 绉嶏級

| 鏍煎紡 | 澶勬暟 | 绀轰緥浣嶇疆 |
|---|---|---|
| `YYYY-MM-DD HH:mm`锛坄replace('T',' ').slice(0,16)`锛?| **10** | `admin/utils/format.js:13`锛?*銆岀粺涓€銆嶅疄鐜?*锛? 9 澶勯〉闈㈠唴鑱?鏈湴閲嶅畾涔?|
| `YYYY-MM-DD HH:mm:ss`锛坄.slice(0,19)`锛?| 2 | `admin/views/platform/tenants.vue:53`銆乣admin/views/tenant/reports.vue:140` |
| Element-Plus `value-format="YYYY-MM-DD"` | 2 | `reports.vue:16`銆乣shift.vue:119` |
| 鎵嬪伐 `getFullYear/getMonth/getDate` + `padStart` | 1 | `admin/views/tenant/shift.vue:161-163` |
| `toLocaleTimeString('zh-CN',{hour12:false})` 鈫?`HH:mm:ss` | 1 | `admin/views/tenant/orders.vue:568` |
| `toLocaleTimeString(...,{hour:'2-digit',minute:'2-digit'})` 鈫?`HH:mm` | 1 | `admin/views/tenant/orders.vue:651` |
| `M鏈圖鏃 | 3 | `c-end/app.js:37,322`銆乣b-end/views/Reservations.vue:106` |
| `M鏈圖鏃?HH:mm` | 1 | `c-end/app.js:709-714` |
| `toISOString().slice(0,10)`锛圲TC锛?| 2 | `c-end/app.js:33,655` |
| 瑁?`toISOString()` | 1 | `c-end/app.js:316` |

**璐熷悜缁撹锛堝ソ锛?*锛歚toLocaleDateString` 鍏ㄤ粨 **0**锛?*12 澶?* `toLocaleString/toLocaleTimeString` **鍏ㄩ儴**鏄惧紡浼犱簡 `'zh-CN'`锛屼笉瀛樺湪銆屾湭浼?locale 鐨?locale 渚濊禆 API銆嶉闄╋紱`dayjs` / `moment` 鍏ㄤ粨 **0**锛堟棤闇€寮曞叆鏂颁緷璧栵級銆傚敮涓€娈嬩綑 ICU 渚濊禆锛歚c-end/app.js:899` 鐨?`Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'})`锛堝彲鑳芥覆鏌撲负 `楼` 鎴?`CN楼`锛夈€?
**`utils/format.js` 閲囩撼鐜?*锛歚formatTime` / `formatYuan` / `currencyText` / `promotionTypeText` 宸茶 `orders.vue` + `api/request.js` 浣跨敤锛沗formatYuanValue`銆乣resourceStateText` **0 璋冪敤鐐?*锛沗admin` 浠嶆湁 **9 澶?*鏈湴閲嶅瀹氫箟 `function formatTime`銆?
### 7.5 鍓嶇鏃犱换浣曡川閲忛棬绂?
`open-saas-admin` / `open-saas-mobile` **閮戒笉瀛樺湪** `.github/`銆乣scripts/`銆乣tools/`銆乣.husky/`銆乣eslint.config.*`銆乣.eslintrc*`銆乣.prettierrc*`銆乣.editorconfig`銆乣.stylelintrc*`锛屼篃鏃?`lint` / `lint:i18n` 鑴氭湰銆傚敮涓€鐨勮嚜鍔ㄩ棬绂佹槸锛?
```
open-saas-admin:  check = "npm test && npm run build && npm audit --omit=dev --audit-level=high"
open-saas-mobile: check = "npm test && npm run build && npm audit --omit=dev --audit-level=high"
```

**浣嗙幇鏈夋祴璇曚簨瀹炰笂鏄€屾枃妗堥攣銆?* 鈥斺€?浠讳綍鏀硅瘝閮戒細璁╁畠浠け璐ワ紝蹇呴』涓€骞舵洿鏂帮細
`open-saas-admin/src/utils/format.test.js:19,20,31,32,42,43`锛坄'楼 123.45'`銆乣'楼 0.00'`銆乣'浜烘皯甯侊紙鍏冿級'`銆乣'鏁村崟鎶樻墸'`銆乣'浼樻儬鍒?`锛夈€乣src/utils/ktv-config.test.js:10`锛坄expect(source).not.toContain('BFF 楠ㄦ灦鍥炴樉')`锛夈€乣open-saas-mobile/c-end/auth-errors.test.js:13,14,15,26,36,47,48,60`銆乣c-end/context.test.js:16,29,30`銆?
---

## 闄勫綍 A锛氬彲閮ㄧ讲鏈嶅姟 脳 缁熶竴鍑哄彛 涓€瑙堣〃

| 鏈嶅姟 | 绔彛 | `@RestController` 鏁?| 缁熶竴鍑哄彛 | `Exception` 鍏滃簳 | 鍏滃簳璁板爢鏍?| 鍏滃簳鍚?code | `@Slf4j` |
|---|---|---|---|---|---|---|---|
| platform-admin | 4150 | 12 | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| platform-customer | 4160 | 5 | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| platform-identity | 4100 | 6 | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| platform-marketing | 4170 | 4 | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| platform-order | 4130 | 15 | 鉁?| 鉁?| 鉁?| 鉂?| 鉁?|
| platform-resource | 4120 | 5 | 鉁?| 鉁?| 鉁?| 鉁?| 鉁?|
| platform-tenant | 4110 | 9 | 鉁?| 鉁?| 鉁?| 鉁?| 鉁?|
| common-audit | 4190 | 1 | **鉂?* | 鈥?| 鈥?| 鈥?| 鉂?|
| common-mail | 4210 | 1 | **鉂?* | 鈥?| 鈥?| 鈥?| 鉂?|
| common-media | 3600 | 7 | 鉁?| 鉁?| 鉂?| 鉂?| 鉂?|
| common-payment | 4140 | 8 | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| common-payment-channel | 4180 | 2 | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| common-sms | 4200 | 1 | **鉂?* | 鈥?| 鈥?| 鈥?| 鉂?|
| im-admin | 3400 | 16 | 鉁?| 鉁咃紙娉勯湶 message锛?| 鉂?| 鉂?| 鉂?|
| im-conversation | 3300 | 15 | 鉁?| 鉁咃紙娉勯湶 message锛?| 鉂?| 鉂?| 鉂?|
| im-message | 3200 | 6 | 鉁?| 鉁咃紙娉勯湶 message锛?| 鉂?| 鉂?| 鉂?|
| im-user | 3100 | 18 | 鉁?| 鉁咃紙娉勯湶 message锛?| 鉂?| 鉂?| 鉂?|
| group-idaas | 4220 | 3 | 鉁?| 鉂?| 鈥?| 鈥?| 鉂?|
| gateways/gateway | 3002 | 0锛圵ebFlux锛?| N/A | 鈥?| 鈥?| 鈥?| 鉁咃紙Filter锛?|
| gateways/im-access-ws | 3001 | 0锛圵ebSocket锛?| N/A | 鈥?| 鈥?| 鈥?| 鈥?|

## 闄勫綍 B锛氬璁℃柟娉曚笌鍙鐜版€?
- **闈欐€?*锛歚read` / `grep`锛坮ipgrep锛? `glob` 宸ュ叿锛屾帓闄?`target/`锛沗src/test` 浠呯敤浜庝氦鍙夐獙璇侊紙鏈姤鍛婁富缁撹鍧囧彇鑷?`src/main`锛夈€?- **鍔ㄦ€?*锛歚kubectl -n gv-im-local exec saas-admin-... -- sh -s` 绠￠亾鍐呰仈鑴氭湰锛屼竴娆℃€ф壂鍏?18 涓湇鍔★紙閬垮厤澶ч噺 port-forward锛夛紱`gateway :30002` 涓?`saas-admin :30081` 璧?NodePort锛涚鐞嗗憳浼氳瘽鐢?`POST /api/v1/admin/auth/login`锛坄admin` / `芦宸茶疆鎹?绠＄悊鍛樺垵濮嬪彛浠ぢ籤锛屾潵鑷?`platform-admin-service/src/main/resources/db/migration/V2__saa_admin_account_seed.sql`锛夊彇寰楋紝闅忓悗 `GET /api/v1/admin/auth/csrf` + `POST /api/v1/admin/context/select {"contextId":"100:100:100"}`銆?- **鍙纭**锛氶櫎鐧诲綍/閫夋嫨涓婁笅鏂囷紙浼氳瘽鎬侊紝闈炰笟鍔℃暟鎹彉鏇达級涓庢棤鏁堣姹傛帰閽堝锛屾湭鍒涘缓/淇敼/鍒犻櫎浠讳綍涓氬姟鏁版嵁锛涙湭鎵ц浠讳綍鍐欐搷浣滃瀷涓氬姟鎺ュ彛锛涙湭瑙︾ `k8s/`锛堣鐩綍鍦ㄥ璁″紑濮嬫椂宸插瓨鍦ㄤ粬浜烘湭鎻愪氦鏀瑰姩锛歚k8s/kind/local-cluster.yaml`銆乣k8s/local/gateway-admin.yaml`銆乣k8s/local/infrastructure.yaml`銆乣k8s/local/ingress.yaml` 鈥斺€?鍧囦负鍏跺畠 agent 鐨勫伐浣滐紝鏈璁℃湭鍙備笌锛夈€?

