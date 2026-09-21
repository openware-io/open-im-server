-- 运营入口必须进入已登记的 A380 B 端 OAuth/OIDC 页面，不能把 app:// 协议交给 WebView。
UPDATE adm_miniapp_service_item
SET link = 'https://miniservice.dev.example.com/b/',
    introduction = '运营后台：开台/计时/结台/收款',
    audience = 'operator',
    hidden = 1,
    status = 1,
    updated_at = NOW(3)
WHERE audience = 'operator'
  AND (name = 'KTV 业务' OR link = 'app://business/ktv');
