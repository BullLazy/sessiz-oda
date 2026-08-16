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
- Uygulama arka planda veya son uygulamalardan kapatılmışken kayıtlı odalar için içeriği göstermeyen bildirim
- Bağlı oda üyeleri arasında uçtan uca şifreli görsel ve video aktarımı
- 100 MB görsel ve 500 MB video üst sınırı; medya aktarılırken metin mesajlarının beklemeden ilerlemesi
- Yoğun mesaj veya medya trafiğinde kullanıcıyı odadan atan hız/MB zaman aşımının kaldırılması
- Aynı cihaz yeniden bağlandığında eski oturumun anında değiştirilmesi ve hayalet kişi sayısının önlenmesi
- Ekran görüntüsü ve son uygulamalar önizlemesinin Android tarafında engellenmesi
- Sıfır harici Node.js paketiyle çalışan küçük WebSocket relay
- Oda başına varsayılan en fazla 50 üye; bildirim izleyicileri kişi sayısına eklenmez

## Önemli: iki parça birlikte çalışır

GitHub Actions yalnızca Android APK'sini üretir. Sohbetin çalışması için `server/` klasöründeki relay uygulamasının internette açık ve TLS kullanan bir sunucuda çalışması gerekir. Uygulamaya bu adres `wss://alan-adiniz.com/chat` biçiminde girilir.

Bu sürümde `server/server.js` de değişti. ZIP'i GitHub'a yükledikten sonra APK'yı almak tek başına yeterli değildir; Render servisinde son commit'i yeniden dağıtın. Eski relay çalışırsa büyük medya/yoğun mesaj kopma düzeltmeleri, hayalet oturum temizliği ve kayıtlı oda bildirimleri devreye girmez.

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
| `MAX_ROOM_SIZE` | `50` | Aynı odadaki en fazla üye (2–100) |
| `MAX_CONNECTIONS` | `500` | Sunucudaki toplam üye ve bildirim bağlantısı sınırı (3–2000) |

Sunucunun kendisi hiçbir `console` çıktısı, dosya, veritabanı veya mesaj kuyruğu oluşturmaz.

## Bildirim ve medya davranışı

- Android 13 ve üzerinde uygulama ilk bağlantıda bildirim izni ister.
- Mesaj bildirimi göndereni, oda adını veya mesaj içeriğini göstermez; yalnız “Yeni bir bildirim var” yazar.
- Bir odaya bu sürümle en az bir kez girildikten sonra uygulama, parola kaydetmeden o odanın genel etkinlik bildirimini izleyebilir.
- Bildirim izleyicisi mesajın şifreli paketini dahi almaz ve odadaki kişi sayısına eklenmez.
- Android düşük öncelikli bir foreground-service bildirimi gösterir. Uygulamayı son uygulamalardan kapatmak servisi durdurmaz; cihaz yeniden başlatılırsa, uygulama zorla durdurulursa veya üreticinin pil yönetimi servisi engellerse uygulamayı yeniden açmak gerekir.
- Arka plan bildirimi çevrimdışı mesaj teslimi değildir. Relay geçmiş tutmadığından, bağlantının gerçekten kesik olduğu sırada gönderilen içerik sonradan indirilemez.
- Görseller en fazla 100 MB, videolar en fazla 500 MB olabilir. Büyük aktarım sırasında metin mesajları küçük bir WebSocket kuyruğuyla öncelikli ilerler.
- Bir alıcının ağı yavaşlarsa relay göndereni odadan atmak yerine akışı geçici olarak yavaşlatır; birikmiş veriyi sınırsız biçimde belleğe doldurmaz.
- Medya sunucuda veya harici depolamada tutulmaz. Yalnız o anda bağlı ve medya desteği bulunan cihazlara şifreli parçalar hâlinde iletilir.
- Çevrimdışı kullanıcıya mesaj veya medya sonradan teslim edilmez.
- Alınan medya, oda süresi dolana kadar uygulamanın özel alanında cihaz anahtarıyla şifreli saklanır. Görüntülemek için açılan geçici kopyalar odadan çıkınca veya sonraki servis başlangıcında temizlenir.
- Uygulama, relay'in medya ve bildirim protokolünü katılım sırasında doğrular. Render eski sürümdeyse ilgili özellik için yeniden dağıtım uyarısı verir.

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
- Kayıtlı odanın arka plan etkinliğini doğrulamak için oda özeti ve parola kanıtı cihaz anahtarıyla şifreli yerel kayıtta tutulur; ortak parolanın kendisi ve mesaj çözme anahtarı kalıcı olarak tutulmaz.
- Cihaza özel rastgele istemci kimliği eski aynı-cihaz bağlantısını değiştirmek ve kendi gönderimine bildirim üretmemek için kullanılır.
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
- **Kişi sayısı bir fazla:** Tüm telefonlarda yeni APK'nın, Render'da da son sunucu sürümünün çalıştığını kontrol edin. Aynı cihazın eski bağlantısı artık yeni oturum geldiği anda değiştirilir.
- **Bildirim gelmiyor:** Android uygulama ayarlarından Sessiz Oda bildirim iznini ve pil kullanımında arka plan çalışmasını kontrol edin.
- **Medya gitmiyor:** İki cihazın da güncel APK'yı kullandığını, o anda odaya bağlı olduğunu ve dosyanın boyut sınırını aşmadığını kontrol edin.
- **“Sunucu eski sürümde” uyarısı:** Render'da **Manual Deploy → Deploy latest commit** çalıştırın. Ardından `/health` yanıtında `"protocol":3`, `"media":true` ve `"notifications":true` bulunduğunu kontrol edin.
- **APK güncellenmiyor:** Eski debug APK'yı kaldırıp yenisini kurun.

## Proje yapısı

```text
.github/workflows/android-apk.yml   GitHub Actions derlemesi
app/                                Android Java uygulaması
server/                             Geçmişsiz WebSocket relay ve testleri
README.md                           Kurulum ve kullanım
PRIVACY.md                          Veri ve gizlilik özeti
```
