# Gizlilik özeti

Sessiz Oda hesap oluşturmaz ve uygulama geliştiricisine veri göndermez. Uygulamada analitik, reklam, crash-reporting, dosya kaydı veya yerel mesaj veritabanı bulunmaz.

Mesaj ve görünen ad, ortak paroladan türetilen anahtarla cihazda AES-256-GCM kullanılarak şifrelenir. Ortak parola relay sunucusuna gönderilmez. Relay yalnız o anda bağlı aynı odadaki cihazlara şifreli paketi iletir; geçmiş saklamaz ve çevrimdışı teslim yapmaz.

Çalışan bir sohbet sırasında relay belleğinde aktif bağlantılar, oda özeti, parola kanıtı, bağlantı sayısı ve hız sınırı sayaçları bulunur. Bağlantı kapanınca ilgili bellek girdileri silinir. Uygulama içi mesaj ekranı da yalnız RAM'dedir ve oturum bitince temizlenir.

Relay'in önündeki barındırma sağlayıcısı, işletim sistemi veya ters proxy IP adresi ve bağlantı zamanı gibi ağ metaverilerini kendi ayarlarına göre kaydedebilir. Bu proje bu dış sistemlerin politikasını kontrol edemez. Böyle bir kayıt istenmiyorsa relay ve TLS proxy kullanıcı tarafından yönetilen bir sunucuda çalıştırılmalı, erişim kayıtları o katmanlarda da kapatılmalıdır.

