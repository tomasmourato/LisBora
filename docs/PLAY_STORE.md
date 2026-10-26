# Publicar a LisBora

Este guia explica a chave de release, como publicar um APK no GitHub, o que já está feito no projeto e o que falta fazer na Play Console. As regras da Google Play mudam: confirma cada ponto na Console antes de enviar.

## No projeto (feito)

- `targetSdk` 37, bibliotecas nativas alinhadas a 16 KB (verificado no APK de release), R8 e redução de recursos no release.
- Perfil de arranque em `app/src/main/baseline-prof.txt` (gravado com `:baselineprofile`).
- Política de privacidade em [docs/PRIVACIDADE.md](PRIVACIDADE.md), também em "Sobre a LisBora" na app. Endereço público: `https://github.com/tomasmourato/LisBora/blob/main/docs/PRIVACIDADE.md`.
- Licenças de código aberto e atribuição dos dados no ecrã "Sobre a LisBora".
- Cópia de segurança do Android sem os pacotes de dados (passariam o limite de 25 MB).
- Chave de release criada e primeiro APK publicado no GitHub ([v0.1.0](https://github.com/tomasmourato/LisBora/releases/tag/v0.1.0)).
- Ficha da loja, respostas aos formulários, ícone, gráfico de destaque e capturas de ecrã em [play/](play/FICHA.md).

## Chave de release

A chave foi criada a 26/10/2026, para o primeiro APK publicado no GitHub (v0.1.0), e está fora do repositório:

- Ficheiro: `C:/Users/mrttm/lisbora-release.jks` (PKCS12, alias `lisbora`, RSA 4096, válida por 10 000 dias).
- Certificado: `CN=LisBora, C=PT`, com o SHA-256 `d6:86:7b:c1:5c:3e:08:02:71:6a:78:c1:d4:69:2b:8f:50:66:45:95:75:96:2f:21:11:19:3a:7b:c8:00:17:2d`.
- Palavra-passe: é a mesma para o ficheiro e para a chave, e está só em `~/.gradle/gradle.properties` (nunca no repositório):

  ```properties
  lisbora.signing.storeFile=C:/Users/mrttm/lisbora-release.jks
  lisbora.signing.storePassword=...
  lisbora.signing.keyAlias=lisbora
  lisbora.signing.keyPassword=...
  ```

Guarda uma cópia dos dois ficheiros fora do PC. **Não cries outra chave.** Um telemóvel só aceita uma versão nova da app assinada com a mesma chave. Se a chave se perder, quem tem a app tem de a desinstalar, e perde os favoritos e as rotas guardadas.

Sem estas propriedades (noutro PC ou no CI), o release é assinado com a chave de debug. Esse APK serve para testar, mas não se instala por cima do APK publicado e a Play recusa-o. O release e o debug também não se instalam um por cima do outro: desinstala o que lá está primeiro.

## Publicar um APK no GitHub

1. Sobe o `versionCode` (sempre mais 1) e o `versionName` em `app/build.gradle.kts`. Faz o commit e o push na `main`.
2. Compila:

   ```bash
   ./gradlew :app:assembleRelease
   ```

   O APK fica em `app/build/outputs/apk/release/app-release.apk`.

3. Confirma a assinatura. O comando tem de mostrar `CN=LisBora, C=PT` e o SHA-256 da secção anterior:

   ```bash
   "$ANDROID_HOME/build-tools/<versão>/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

4. Experimenta-o no emulador: instala-o, abre-o, faz uma pesquisa e calcula uma rota. É aí que o R8 pode partir alguma coisa.
5. Escreve as notas do release em pt-PT, com as do [v0.1.0](https://github.com/tomasmourato/LisBora/releases/tag/v0.1.0) como modelo: o que mudou, como instalar, o SHA-256 do APK (`sha256sum`) e o do certificado, e as licenças.
6. Copia o APK com o nome da versão e cria o release. Usa o SHA completo do commit na tag, porque o GitHub recusa um SHA abreviado:

   ```bash
   cp app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/LisBora-0.2.0.apk
   gh release create v0.2.0 app/build/outputs/apk/release/LisBora-0.2.0.apk --target "$(git rev-parse HEAD)" --title "LisBora 0.2.0" --notes-file notas.md --latest
   ```

Não mexas no release `datapack`: é um pré-lançamento à parte, de onde a app descarrega os dados novos, e é gerido pelo workflow Dados.

## App Bundle para a Play

Sobe o `versionCode` (e o `versionName`) em `app/build.gradle.kts` a cada envio e gera o bundle:

```bash
./gradlew :app:bundleRelease
```

O ficheiro fica em `app/build/outputs/bundle/release/app-release.aab` (~49 MB), assinado com a chave de release, que também serve de chave de upload. A primeira versão para a Play é a 0.1.1 (`versionCode` 2).

## Na Play Console, passo a passo

Os textos, os gráficos e as respostas aos formulários estão em [play/FICHA.md](play/FICHA.md), e as imagens em `docs/play/`. O vídeo do serviço em primeiro plano (`docs/play/video-servico-primeiro-plano.mp4`, 8,8 MB) não está no git: grava-se no emulador como está descrito no fim deste guia.

1. **Conta de programador**: cria-a em [play.google.com/console](https://play.google.com/console). A inscrição custa uma taxa única de 25 USD e pede a verificação da identidade.
   - Numa conta pessoal criada depois de 13/11/2023, a produção só abre depois de um **teste fechado com pelo menos 12 testadores inscritos durante 14 dias seguidos** (passo 6).
   - Uma conta de organização não tem este requisito, mas precisa de um número D-U-N-S.
2. **Criar a app**: nome "LisBora: bicicleta em Lisboa", idioma predefinido português (Portugal), tipo App, gratuita. Aceita as declarações.
3. **Assinatura (antes de enviar qualquer bundle)**: em "Teste e lançamento > Configuração > Integridade da app > Assinatura de apps", escolhe usar a tua própria chave: exportar e carregar uma chave de um keystore Java.
   - A Console dá o `pepk.jar`, uma chave de encriptação e um comando deste género:

     ```bash
     java -jar pepk.jar --keystore=C:/Users/mrttm/lisbora-release.jks --alias=lisbora --output=lisbora-chave.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
     ```

   - O comando pede a palavra-passe do keystore (está em `~/.gradle/gradle.properties`). Carrega o `.zip` na Console.
   - Se enviares um bundle antes deste passo, a Google gera outra chave e as instalações da Play deixam de se atualizar por cima das do GitHub. Isto não tem volta atrás.
4. **Ficha da loja** ("Crescimento > Presença na loja > Ficha principal da loja"): os textos, o ícone, o gráfico de destaque e as 7 capturas da [FICHA](play/FICHA.md). Em "Definições da loja": a categoria Mapas e navegação, o email e o site.
5. **Conteúdo da app** ("Política > Conteúdo da app"): preenche os formulários com as respostas da [FICHA](play/FICHA.md): política de privacidade, anúncios, acesso, classificação, público-alvo, segurança dos dados, ID de publicidade e serviço em primeiro plano.
   - O serviço em primeiro plano pede um endereço do YouTube. Carrega lá o vídeo como "Não listado".
6. **Teste fechado**: em "Teste e lançamento > Testes > Teste fechado", cria uma faixa e junta os testadores (uma lista de emails ou um Grupo Google). Escolhe Portugal como país.
   - Cria uma versão com o `app-release.aab` e as notas da [FICHA](play/FICHA.md) e envia-a para revisão.
   - Depois de aprovada, partilha o link de inscrição. Cada testador tem de aceitar o convite e instalar a app pela Play.
   - Conta 14 dias com 12 ou mais testadores inscritos sem interrupção. Quem sair e voltar recomeça a contagem. Pede-lhes que usem a app e deixem comentários: o pedido de produção pergunta por isso.
7. **Pedir acesso à produção** (Painel > "Candidatar-se à produção"): a Console pergunta:
   - como recrutaste os testadores e o que comentaram;
   - o que mudaste depois do teste;
   - para quem é a app e quantas instalações esperas no primeiro ano (por exemplo, "menos de 10 000").

   A revisão costuma demorar até 7 dias.
8. **Produção**: em "Teste e lançamento > Produção", cria uma versão com o mesmo bundle (ou com um `versionCode` mais alto) e envia-a para revisão. Países: Portugal (ou todos).

## Gravar o vídeo do serviço em primeiro plano

Com o emulador ligado, a app instalada e a localização simulada (ver o `CLAUDE.md`), grava até 3 min:

```bash
MSYS_NO_PATHCONV=1 adb shell screenrecord --time-limit 70 /sdcard/fgs.mp4
```

Durante a gravação, faz o seguinte:

1. Pesquisa um destino e carrega em "Direções" e em "Iniciar".
2. Envia algumas posições GPS para a navegação arrancar.
3. Carrega em Início e abre a gaveta de notificações: a indicação continua na notificação, com "Terminar".
4. Volta à app e termina a volta.

Depois copia o vídeo com `MSYS_NO_PATHCONV=1 adb pull /sdcard/fgs.mp4 docs/play/video-servico-primeiro-plano.mp4`.

## Antes de publicar

- O código ainda não tem licença (README). Não é preciso para a Play, mas convém decidir antes de divulgar.
- **Risco na revisão: os dados em tempo real da GIRA.** A app lê a base Firestore da VAIMOO com a chave da app oficial da GIRA, uma API não documentada (exceção registada no `CLAUDE.md`). A política da Play sobre abuso de dispositivos e redes proíbe apps que usem um serviço ou uma API contra os termos de serviço deles. Se a revisão o apontar, a saída é uma versão para a Play sem as contagens em direto: o modo GIRA continua com as estações do pacote.
- Os dados da GIRA vêm de uma API que pode deixar de responder; nesse caso a app continua com as estações do pacote.
- **Verificação de programadores do Android**: quem publica na Play fica registado automaticamente. A Google vai exigir que as apps instaladas fora da Play (como o APK do GitHub) sejam de programadores verificados e com o pacote e a chave registados. Começa a 30/09/2026 no Brasil, na Indonésia, em Singapura e na Tailândia e chega ao resto do mundo, incluindo Portugal, a partir de 2027. Antes disso, regista o `pt.lisbora` e a chave de release para a distribuição fora da Play, na Play Console.
