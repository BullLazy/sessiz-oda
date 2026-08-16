# Gizlilik özeti

Sessiz Oda hesap oluşturmaz ve uygulama geliştiricisine veri göndermez. Uygulamada analitik, reklam veya crash-reporting bulunmaz. Relay sunucusu mesaj geçmişi saklamaz.

Mesaj, görünen ad, medya içeriği, dosya adı ve medya türü ortak paroladan türetilen anahtarla cihazda AES-256-GCM kullanılarak şifrelenir. Ortak parola relay sunucusuna gönderilmez. Relay yalnız o anda bağlı aynı odadaki cihazlara şifreli paketleri iletir; geçmiş saklamaz ve çevrimdışı teslim yapmaz.

Çalışan bir sohbet sırasında relay belleğinde aktif bağlantılar, oda özeti, parola kanıtı, rastgele cihaz istemci kimliği ve bağlantı sayısı bulunur. Relay mesaj veya medya yoğunluğu yüzünden kullanıcıyı kapatan hız/MB sayacı tutmaz. Medya aktarımında relay paketlerin medya sınıfında olduğunu, şifreli boyutlarını ve aktarım zamanını görür; içeriği ve dosya adını okuyamaz. Bağlantı kapanınca ilgili bellek girdileri silinir.

Kullanıcının seçtiği 1 saat, 6 saat, 24 saat, 3 gün veya 7 günlük süre boyunca mesajlar ve medya ilgili telefonda saklanır. Bu yerel kayıt AES-256-GCM ile şifrelenir; anahtar Android Keystore içinde oluşturulur ve uygulamanın veri alanından çıkarılamaz. Kayıtlar bulut veya cihaz yedeğine dâhil edilmez.

Görünen ad, sunucu adresi, oda kodu, seçilen süre, rastgele istemci kimliği ve kayıtlı oda listesi aynı şifreli yerel kayıtta tutulur. Arka plan etkinlik bağlantısını doğrulamak için oda özeti ile parola kanıtı da bu kayıtta tutulur. Bu kanıt oda üyeliği açısından hassastır ancak mesajları çözmeye yetmez. Ortak parola ve mesaj çözme anahtarı kalıcı olarak kaydedilmez. Süre dolunca sohbet içeriği ve şifreli medya dosyaları silinir, oda profili ve bildirim erişimi boş sohbetle birlikte kalır. Oda açıkken silme zamanında uygulanır; uygulama çalışmıyorsa bir sonraki açılışta geçmiş gösterilmeden önce uygulanır.

Alınan görsel ve videolar görüntülenebilmek için ayrıca uygulamanın özel geçici önbelleğinde çözülür. Bu açık kopyalar odadan çıkınca silinir; beklenmedik kapanmadan kalan önbellek sonraki servis başlangıcında temizlenir.

Android bildirimi mesaj içeriğini, göndereni veya oda adını göstermez; yalnız “Yeni bir bildirim var” ifadesini kullanır. Kayıtlı oda izleyicisine şifreli mesaj veya medya paketi gönderilmez; relay yalnız o odada yeni etkinlik olduğunu bildirir. İzleyici kişi sayısına katılmaz ve aynı cihazdan gönderilen içerik için bildirim oluşturulmaz. Uygulama Firebase Cloud Messaging, analitik veya reklam SDK'sı kullanmaz. Arka plan bağlantısı Android foreground service ile sürdürülür ve işletim sistemi düşük öncelikli bir bağlantı bildirimi gösterir. Son uygulamalar ekranından kapatma servisi durdurmaz; zorla durdurma, cihazı yeniden başlatma veya üretici pil kısıtları sonrasında uygulamanın tekrar açılması gerekebilir.

Relay'in önündeki barındırma sağlayıcısı, işletim sistemi veya ters proxy IP adresi ve bağlantı zamanı gibi ağ metaverilerini kendi ayarlarına göre kaydedebilir. Bu proje bu dış sistemlerin politikasını kontrol edemez. Böyle bir kayıt istenmiyorsa relay ve TLS proxy kullanıcı tarafından yönetilen bir sunucuda çalıştırılmalı, erişim kayıtları o katmanlarda da kapatılmalıdır.
