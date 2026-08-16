# Sessiz Oda

Sessiz Oda, küçük özel gruplar için hazırlanmış bir Android sohbet uygulamasıdır. Sunucu geçmiş tutmaz; uygulama seçilen süre dolana kadar sohbeti yalnız ilgili telefonda, cihaz anahtarıyla şifreli biçimde saklar. Hesap, analitik, reklam veya uygulama içi log yoktur.

## Neler var?

- Android 8.0 ve üzeri için kurulabilir APK
- GitHub Actions üzerinden otomatik lint, test ve APK derlemesi
- Ortak parola ile cihaz üzerinde AES-256-GCM şifreleme
- Parolanın sunucuya hiç gönderilmemesi
- Mesajların sunucuda saklanmaması ve sonradan gelen kişiye eski mesaj verilmemesi
- Oda başına 1 saat, 6 saat, 24 saat, 3 gün veya 7 gün süreli şifreli yerel geçmiş
- Süre dolunca sohbetin ve yerel medya kopyalarının silinmesi; kayıtlı oda kartının kalması
- Görünen ad, sunucu adresi ve oda kodunun cihazda şifreli biçimde hatırlanması
- Ortak parolanın hiçbir zaman kalıcı olarak kaydedilmemesi
- Daha önce girilen odaları tek dokunuşla dolduran kayıtlı oda kartları
- Mesajlarda cihazın saat biçimine uygun gönderim saati
- Mesaja uzun basarak metni panoya kopyalama
- Yazma alanının ekran klavyesinin üzerinde kalması
- Tek karakterli iletilerde de ad, mesaj ve saati düzgün gösteren sabit genişlikte balon
- Telefon yatay veya dikey çevrildiğinde açık oturumun korunması
- Uygulama arka plandayken içeriği göstermeyen “Yeni bir bildirim var” bildirimi
- Bağlı oda üyeleri arasında uçtan uca şifreli görsel ve video aktarımı
- Ekran görüntüsü ve son uygulamalar önizlemesinin Android tarafında engellenmesi
- Sıfır harici Node.js paketiyle çalışan küçük WebSocket relay
- Oda başına varsayılan en fazla 10 bağlantı ve basit hız sınırı

## Önemli: iki parça birlikte çalışır

GitHub Actions yalnızca Android APK'sini üretir. Sohbetin çalışması için `server/` klasöründeki relay uygulamasının internette açık ve TLS kullanan bir sunucuda çalışması gerekir. Uygulamaya bu adres `wss://alan-adiniz.com/chat` biçiminde girilir.

## 1. Sunucuyu çalıştırın

Sunucuda Node.js 22 veya daha yenisi varsa:

```bash
cd server
npm test
PORT=8080 node server.js
```

Docker ile:

```bash
cd server
docker compose up -d --build
```

`compose.yaml`, servisi yalnızca `127.0.0.1:8080` üzerinde açar. Bunun önüne TLS sağlayan Caddy veya Nginx koyun. Örnek Caddy yapılandırması:

```caddyfile
chat.ornekalan.com {
    reverse_proxy 127.0.0.1:8080
}
```

Bu örnekte telefona girilecek adres:

```text
wss://chat.ornekalan.com/chat
```

Bir Docker barındırma hizmeti de kullanılabilir. Çalışma dizini `server`, port `8080`, sağlık kontrolü `/health` olmalıdır. Barındırma hizmetinin kendi erişim kaydı politikası ayrıca kontrol edilmelidir.

Kullanılabilen ortam değişkenleri:

| Değişken | Varsayılan | Açıklama |
| --- | ---: | --- |
| `PORT` | `8080` | Relay'in dinlediği port |
| `MAX_ROOM_SIZE` | `10` | Aynı odadaki en fazla bağlantı (2–50) |
| `MAX_CONNECTIONS` | `50` | Sunucudaki toplam bağlantı sınırı |

Sunucunun kendisi hiçbir `console` çıktısı, dosya, veritabanı veya mesaj kuyruğu oluşturmaz.

## Bildirim ve medya davranışı

- Android 13 ve üzerinde uygulama ilk bağlantıda bildirim izni ister.
- Mesaj bildirimi göndereni, oda adını veya mesaj içeriğini göstermez; yalnız “Yeni bir bildirim var” yazar.
- Bağlantının arka planda açık kalması için Android ayrıca düşük öncelikli “Odaya bağlı” hizmet bildirimi gösterir.
- Görseller en fazla 8 MB, videolar en fazla 20 MB olabilir.
- Medya sunucuda veya harici depolamada tutulmaz. Yalnız o anda bağlı ve medya desteği bulunan cihazlara şifreli parçalar hâlinde iletilir.
- Çevrimdışı kullanıcıya mesaj veya medya sonradan teslim edilmez.
- Alınan medya, oda süresi dolana kadar uygulamanın özel alanında cihaz anahtarıyla şifreli saklanır. Görüntülemek için açılan geçici kopyalar odadan çıkınca veya sonraki servis başlangıcında temizlenir.
- Uygulama, relay'in medya protokolünü katılım sırasında doğrular. Render eski sürümdeyse medya düğmesi devre dışı kalır; böylece yarım paket yüzünden oda bağlantısı kopmaz.

## Süreli oda geçmişi

- Giriş ekranında oda için 1 saat, 6 saat, 24 saat, 3 gün veya 7 gün seçilir.
- Süre, o telefondaki sohbet geçmişi için geçerlidir. Relay geçmiş ve oda ayarı saklamadığı için diğer telefonlarda aynı süre ayrıca seçilmelidir.
- Süre dolduğunda oda açıksa ekran ve şifreli yerel kayıt hemen temizlenir. Uygulama çalışmıyorsa temizlik bir sonraki açılışta, geçmiş gösterilmeden önce yapılır.
- Oda kartı silinmez; sohbeti boş olarak yeniden kullanabilirsiniz.
- Her telefonda oda başına en fazla 150 sohbet olayı tutulur.

## 2. GitHub Actions ile APK alın

1. GitHub'da tercihen **private** bir depo oluşturun.
2. Bu ZIP'in içindeki tüm dosya ve klasörleri deponun köküne yükleyin. `.github` klasörü mutlaka bulunmalı.
3. GitHub'da **Actions** sekmesine girin.
4. Sol taraftan **Android APK** iş akışını açın.
5. **Run workflow** düğmesine basın.
6. Yeşil tamamlandığında ilgili çalışmanın altındaki **Artifacts** bölümünden `sessiz-oda-apk` dosyasını indirin.
7. İndirilen arşivdeki `sessiz-oda.apk` dosyasını telefonlara kurun.

İş akışı önce relay entegrasyon testini çalıştırır; ardından Android lint, birim testi ve APK derlemesini yapar. Kullanılan derleme eşleşmesi Android Gradle Plugin `8.9.2`, Gradle `8.11.1`, JDK `17`, compile SDK `35` şeklinde sabitlenmiştir.

APK bir debug imzasıyla üretilir ve doğrudan kurulabilir. Farklı GitHub Actions çalışmalarında imza değişebileceği için yeni APK eskisinin üzerine kurulmazsa önce eski sürümü kaldırın. Kalıcı güncelleme imzası istenirse ayrı bir release keystore eklenmelidir.

## 3. Sohbete girin

Her telefonda şu üç değer aynı olmalıdır:

- Sunucu adresi
- Oda kodu
- En az 12 karakterlik ortak parola

Görünen ad farklı olabilir. Yanlış parola giren kişi aynı oda kodunu kullansa bile şifreli mesajları açamaz; uygulamada yalnız görünür.

## Veri davranışı

- Mesaj metni ve görünen ad açık biçimde yalnız RAM'de işlenir; kalıcı yerel kopya cihaz anahtarıyla şifrelenir.
- Sunucuya şifreli paket, oda özeti ve parola kanıtı gider. Düz metin parola gitmez.
- Relay paketi mevcut oda üyelerine iletir ve hemen bırakır.
- Çevrimdışı teslim, sunucu geçmişi ve kullanıcı hesabı yoktur. Bir telefon çevrimdışıyken gönderilen içerik o telefona sonradan gelmez.
- Android uygulaması oda profillerini ve süre dolana kadarki sohbeti uygulamaya özel, yedekleme dışı alanda AES-256-GCM ile şifreli tutar. Şifreleme anahtarı Android Keystore içinde oluşturulur.
- Görünen ad, sunucu adresi ve oda kodu hatırlanır; ortak parola kaydedilmez.
- Uygulama `SharedPreferences`, SQLite/Room, analitik, reklam veya crash-reporting SDK'sı kullanmaz.
- Oda başına en fazla 150 sohbet olayı tutulur. Süre dolduğunda metinler ve şifreli medya dosyaları silinir; kayıtlı oda profili kalır.
- Medya aktarımında relay; içeriği, dosya adını ve MIME türünü okuyamaz fakat medya trafiği olduğunu, şifreli paket boyutlarını ve aktarım zamanını görebilir.

Tam anlamıyla “dünyanın hiçbir katmanında log yok” garantisi uygulama koduyla verilemez. Android işletim sistemi, internet sağlayıcı, ters proxy veya barındırma firması bağlantı zamanı ve IP gibi teknik metaverileri kendi politikasına göre kaydedebilir. Kesin denetim gerekiyorsa relay kendi sunucunuzda çalıştırılmalı ve işletim sistemi/proxy erişim kayıtları ayrıca kapatılmalıdır. Mesaj içeriği yine uçtan uca şifrelidir.

## Sorun giderme

- **Actions iş akışı görünmüyor:** `.github/workflows/android-apk.yml` dosyasının depoda bulunduğunu kontrol edin.
- **Bağlantı kurulmuyor:** Adresin `wss://` ile başladığını, `/chat` yolunu ve TLS sertifikasını kontrol edin.
- **Herkes yalnız “1 kişi bağlı” görüyor:** Oda kodu veya ortak parola telefonlarda birebir aynı değildir.
- **Oda dolu:** Eski bağlantının kapanması en fazla yaklaşık 25 saniye sürebilir.
- **Bildirim gelmiyor:** Android uygulama ayarlarından Sessiz Oda bildirim iznini ve pil kullanımında arka plan çalışmasını kontrol edin.
- **Medya gitmiyor:** İki cihazın da güncel APK'yı kullandığını, o anda odaya bağlı olduğunu ve dosyanın boyut sınırını aşmadığını kontrol edin.
- **“Sunucu eski sürümde” uyarısı:** Render'da **Manual Deploy → Deploy latest commit** çalıştırın. Ardından `/health` yanıtında `"protocol":2` ve `"media":true` bulunduğunu kontrol edin.
- **APK güncellenmiyor:** Eski debug APK'yı kaldırıp yenisini kurun.

## Proje yapısı

```text
.github/workflows/android-apk.yml   GitHub Actions derlemesi
app/                                Android Java uygulaması
server/                             Geçmişsiz WebSocket relay ve testleri
README.md                           Kurulum ve kullanım
PRIVACY.md                          Veri ve gizlilik özeti
```
