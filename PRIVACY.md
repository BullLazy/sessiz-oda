# Gizlilik özeti

Sessiz Oda hesap oluşturmaz ve uygulama geliştiricisine veri göndermez. Uygulamada analitik, reklam, crash-reporting, kalıcı medya kaydı veya yerel mesaj veritabanı bulunmaz.

Mesaj, görünen ad, medya içeriği, dosya adı ve medya türü ortak paroladan türetilen anahtarla cihazda AES-256-GCM kullanılarak şifrelenir. Ortak parola relay sunucusuna gönderilmez. Relay yalnız o anda bağlı aynı odadaki cihazlara şifreli paketleri iletir; geçmiş saklamaz ve çevrimdışı teslim yapmaz.

Çalışan bir sohbet sırasında relay belleğinde aktif bağlantılar, oda özeti, parola kanıtı, bağlantı sayısı ve hız sınırı sayaçları bulunur. Medya aktarımında relay ayrıca paketlerin medya sınıfında olduğunu, şifreli boyutlarını ve aktarım zamanını görür; içeriği ve dosya adını okuyamaz. Bağlantı kapanınca ilgili bellek girdileri silinir.

Metin mesajları uygulamada yalnız RAM'de tutulur. Alınan görsel ve videolar görüntülenebilmek için uygulamanın özel geçici önbelleğinde çözülür. Bu dosyalar odadan çıkınca silinir; beklenmedik kapanmadan kalan önbellek bir sonraki servis başlangıcında temizlenir.

Android bildirimi mesaj içeriğini, göndereni veya oda adını göstermez; yalnız “Yeni bir bildirim var” ifadesini kullanır. Uygulama Firebase Cloud Messaging, analitik veya reklam SDK'sı kullanmaz. Arka plan bağlantısı Android foreground service ile sürdürülür ve işletim sistemi düşük öncelikli bir bağlantı bildirimi gösterir.

Relay'in önündeki barındırma sağlayıcısı, işletim sistemi veya ters proxy IP adresi ve bağlantı zamanı gibi ağ metaverilerini kendi ayarlarına göre kaydedebilir. Bu proje bu dış sistemlerin politikasını kontrol edemez. Böyle bir kayıt istenmiyorsa relay ve TLS proxy kullanıcı tarafından yönetilen bir sunucuda çalıştırılmalı, erişim kayıtları o katmanlarda da kapatılmalıdır.
