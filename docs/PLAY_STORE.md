# Publicar a LisBora

Este guia explica a chave de release, como publicar um APK no GitHub, o que já está feito no projeto e o que falta fazer na Play Console. As regras da Google Play mudam: confirma cada ponto na Console antes de enviar.

## No projeto (feito)

- `targetSdk` 37, bibliotecas nativas alinhadas a 16 KB (verificado no APK de release), R8 e redução de recursos no release.
- Perfil de arranque em `app/src/main/baseline-prof.txt` (gravado com `:baselineprofile`).
- Política de privacidade em [docs/PRIVACIDADE.md](PRIVACIDADE.md), também em "Sobre a LisBora" na app. Endereço público: `https://github.com/tomasmourato/LisBora/blob/main/docs/PRIVACIDADE.md`.
- Licenças de código aberto e atribuição dos dados no ecrã "Sobre a LisBora".
- Cópia de segurança do Android sem os pacotes de dados (passariam o limite de 25 MB).
- Chave de release criada e primeiro APK publicado no GitHub ([v0.1.0](https://github.com/tomasmourato/LisBora/releases/tag/v0.1.0)).

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

1. Sobe o `versionCode` (e o `versionName`) em `app/build.gradle.kts` a cada envio e gera o bundle:

   ```bash
   ./gradlew :app:bundleRelease
   ```

   O ficheiro fica em `app/build/outputs/bundle/release/app-release.aab`.

2. Na primeira vez, a Play Console pede para configurar a assinatura de apps da Play. Escolhe usar a chave acima como chave de assinatura da app, em vez de deixares a Google gerar uma; a Console explica como a exportar. Assim, a versão da Play instala-se por cima da do GitHub, e o contrário também.

## Na Play Console

- **Testes antes da produção**: as contas pessoais criadas depois de novembro de 2023 têm de fazer um teste fechado com pelo menos 12 testadores durante 14 dias antes de publicar.
- **Ficha da loja**
  - Nome: LisBora
  - Descrição curta (até 80 caracteres): "Rotas de bicicleta em Lisboa pelas ciclovias e ruas calmas, sem Internet."
  - Descrição completa: o primeiro parágrafo do [README](../README.md) serve de base. Junta o Descobrir (passeios automáticos para o tempo que tens), a navegação por voz com o ecrã desligado, o modo GIRA e a nota de segurança ("nenhuma rota é garantidamente segura").
  - Categoria: Mapas e navegação. Capturas de ecrã do telemóvel (pelo menos duas): o mapa com a rede ciclável, uma rota com alternativas, a navegação e o Descobrir.
  - Endereço de email de contacto: obrigatório e público. Escolhe um endereço que possas mostrar.
- **Política de privacidade**: o endereço acima.
- **Segurança dos dados**: a app não recolhe nem partilha dados. A localização é tratada só no telemóvel. Os pedidos ao GitHub (dados novos) e à plataforma da GIRA não levam dados pessoais, além do endereço IP de qualquer ligação. As ligações usam HTTPS. Não há contas, por isso não há pedido para apagar uma conta.
- **Serviços em primeiro plano**: declara o tipo `location` para a navegação passo a passo com o ecrã desligado, sempre com uma notificação visível e iniciada pelo utilizador ("Iniciar"). A Console pede um vídeo curto que mostre a funcionalidade.
- **Localização**: só em primeiro plano. A app não pede `ACCESS_BACKGROUND_LOCATION`, por isso não precisa da declaração de localização em segundo plano.
- **Acesso à app**: não precisa de conta nem de credenciais.
- **Anúncios**: não tem.
- **Classificação de conteúdo**: preenche o questionário. Não há conteúdo gerado por utilizadores nem compras.
- **Público-alvo**: não é dirigida a crianças.

## Antes de publicar

- O código ainda não tem licença (README). Não é preciso para a Play, mas convém decidir antes de divulgar.
- Os dados da GIRA vêm de uma API não documentada (exceção registada no `CLAUDE.md`): se deixar de responder, a app continua com as estações do pacote.
