# Gizlilik özeti

Sessiz Oda hesap oluşturmaz ve uygulama geliştiricisine veri göndermez. Uygulamada analitik, reklam veya crash-reporting bulunmaz. Relay sunucusu mesaj geçmişi saklamaz.

Mesaj, görünen ad, medya içeriği, dosya adı ve medya türü ortak paroladan türetilen anahtarla cihazda AES-256-GCM kullanılarak şifrelenir. Ortak parola relay sunucusuna gönderilmez. Relay yalnız o anda bağlı aynı odadaki cihazlara şifreli paketleri iletir; geçmiş saklamaz ve çevrimdışı teslim yapmaz.

Çalışan bir sohbet sırasında relay belleğinde aktif bağlantılar, oda özeti, parola kanıtı, bağlantı sayısı ve hız sınırı sayaçları bulunur. Medya aktarımında relay ayrıca paketlerin medya sınıfında olduğunu, şifreli boyutlarını ve aktarım zamanını görür; içeriği ve dosya adını okuyamaz. Bağlantı kapanınca ilgili bellek girdileri silinir.

Kullanıcının seçtiği 1 saat, 6 saat, 24 saat, 3 gün veya 7 günlük süre boyunca mesajlar ve medya ilgili telefonda saklanır. Bu yerel kayıt AES-256-GCM ile şifrelenir; anahtar Android Keystore içinde oluşturulur ve uygulamanın veri alanından çıkarılamaz. Kayıtlar bulut veya cihaz yedeğine dâhil edilmez.

Görünen ad, sunucu adresi, oda kodu, seçilen süre ve kayıtlı oda listesi aynı şifreli yerel kayıtta tutulur. Ortak parola kalıcı olarak kaydedilmez. Süre dolunca sohbet içeriği ve şifreli medya dosyaları silinir, oda profili boş biçimde kalır. Oda açıkken silme zamanında uygulanır; uygulama çalışmıyorsa bir sonraki açılışta geçmiş gösterilmeden önce uygulanır.

Alınan görsel ve videolar görüntülenebilmek için ayrıca uygulamanın özel geçici önbelleğinde çözülür. Bu açık kopyalar odadan çıkınca silinir; beklenmedik kapanmadan kalan önbellek sonraki servis başlangıcında temizlenir.

Android bildirimi mesaj içeriğini, göndereni veya oda adını göstermez; yalnız “Yeni bir bildirim var” ifadesini kullanır. Uygulama Firebase Cloud Messaging, analitik veya reklam SDK'sı kullanmaz. Arka plan bağlantısı Android foreground service ile sürdürülür ve işletim sistemi düşük öncelikli bir bağlantı bildirimi gösterir.

Relay'in önündeki barındırma sağlayıcısı, işletim sistemi veya ters proxy IP adresi ve bağlantı zamanı gibi ağ metaverilerini kendi ayarlarına göre kaydedebilir. Bu proje bu dış sistemlerin politikasını kontrol edemez. Böyle bir kayıt istenmiyorsa relay ve TLS proxy kullanıcı tarafından yönetilen bir sunucuda çalıştırılmalı, erişim kayıtları o katmanlarda da kapatılmalıdır.
