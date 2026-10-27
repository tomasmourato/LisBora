# Ficha da LisBora na Google Play

Textos e respostas para copiar para a Play Console. O idioma principal da ficha é o português de Portugal (pt-PT), como a app. Os passos estão em [PLAY_STORE.md](../PLAY_STORE.md).

## Ficha principal da loja

**Nome da app** (até 30 caracteres):

```
LisBora: bicicleta em Lisboa
```

**Descrição breve** (até 80 caracteres):

```
Rotas de bicicleta em Lisboa por ciclovias e ruas calmas. Funciona offline.
```

**Descrição completa** (até 4000 caracteres):

```
A LisBora é uma app para andar de bicicleta em Lisboa. Mostra a rede ciclável da cidade no mapa, calcula rotas que preferem ciclovias e ruas calmas e sugere passeios para o tempo que tens. As rotas são calculadas no telemóvel: não precisas de conta nem de Internet.

PLANEAR UMA ROTA
• Até 3 rotas para o teu destino, com o tempo, a distância, a subida e a percentagem em ciclovia.
• Perfil de elevação e avisos do que vem aí: carris de elétrico, calçada, descidas íngremes, trânsito sem ciclovia e troços a pé.
• Preferências para a tua bicicleta e o teu ritmo: cidade, estrada, BTT ou elétrica; inclinação máxima; evitar calçada ou carris; preferir ciclovias segregadas.

DESCOBRIR
Tens 45 minutos? Escolhe o tempo (de 20 min a 2 h) e o motivo, e a LisBora sugere passeios de volta ao ponto de partida ou até um destino:
• Lazer, Paisagens (miradouros), Rio, Desporto, Subidas, Descidas, Família e Cultura.
• Cada passeio diz porque foi escolhido: "8,1 km junto ao rio", "2 miradouros", "Quase plana"…
• "Outra rota", "Mais rio" e "Menos subidas" para ajustar. Guarda os teus preferidos.
• Modo GIRA: de estação em estação, sem sair do concelho de Lisboa, com as bicicletas e docas livres em tempo real e avisos antes do fim dos 45 minutos.

NAVEGAÇÃO PASSO A PASSO
• Indicações em português, no ecrã e por voz, também com o ecrã desligado.
• Se te desvias, a rota é recalculada no telemóvel.
• No fim, um resumo da volta, que podes exportar em GPX. Também podes importar um GPX e segui-lo.

PEDALAR
• Sem destino nem indicações: carrega em "Pedalar" e vai. No fim, vês a distância, o tempo a pedalar, a velocidade média e a subida, e podes exportar a volta em GPX.
• Numa bicicleta GIRA, conta os 45 minutos e diz onde trocar de bicicleta antes de acabarem.

MAPA DE LISBOA
• Rede ciclável da Câmara Municipal de Lisboa, por tipo: ciclovia segregada, faixa ciclável, zonas 30+Bici, percursos ciclopedonais e trilhos.
• Miradouros, jardins, museus, bebedouros, estacionamento de bicicletas, estações GIRA e oficinas.
• Os dados atualizam-se sozinhos, uma vez por dia em Wi-Fi, sem precisares de atualizar a app.

PRIVACIDADE
Sem contas, sem publicidade e sem estatísticas de utilização. A tua localização nunca sai do telemóvel.

ATENÇÃO
Nenhuma rota é garantidamente segura. A LisBora mostra os perigos que conhece, mas a decisão é sempre tua: respeita o Código da Estrada e as condições do momento.

Dados: © contribuidores do OpenStreetMap (ODbL); rede ciclável © Câmara Municipal de Lisboa (CC BY 4.0). Todas as atribuições estão na app, em "Sobre a LisBora".
```

**Gráficos** (em `docs/play/`):

- Ícone: `icone-512.png` (512 × 512).
- Gráfico de destaque: `grafico-destaque-1024x500.png`.
- Capturas de ecrã do telemóvel, por esta ordem: `capturas/1-mapa.jpg` a `capturas/7-legenda.jpg` (1080 × 1920).

## Detalhes da loja

- Categoria: **Mapas e navegação**.
- Etiquetas: escolhe as que a Console propõe para bicicleta, navegação e mapas.
- Email: **obrigatório e público**. Escolhe um endereço que possas mostrar.
- Site: `https://github.com/tomasmourato/LisBora`.
- Política de privacidade: `https://github.com/tomasmourato/LisBora/blob/main/docs/PRIVACIDADE.md`.

## Notas da versão 0.1.1 (até 500 caracteres)

```
<pt-PT>
Primeira versão da LisBora na Google Play: rotas de bicicleta em Lisboa por ciclovias e ruas calmas, passeios para o tempo que tens (Descobrir), modo GIRA e navegação passo a passo por voz, também com o ecrã desligado.
</pt-PT>
```

## Conteúdo da app

Respostas para a secção "Conteúdo da app" (Política > Conteúdo da app):

- **Política de privacidade**: o endereço acima.
- **Anúncios**: a app não tem anúncios.
- **Acesso à app**: todas as funcionalidades estão disponíveis sem acesso especial (não há contas nem início de sessão).
- **Classificação de conteúdo**: no questionário (IARC), escolhe a categoria "Todos os outros tipos de apps". Responde "Não" a violência, conteúdo sexual, linguagem, drogas, álcool, tabaco e jogos de azar. Responde "Não" à interação entre utilizadores e à partilha de conteúdo. Responde também "Não" à partilha da localização com outros utilizadores e às compras digitais. Deve dar classificação para todas as idades.
- **Público-alvo**: 13–15, 16–17 e 18 ou mais anos. Não incluas menores de 13, que trariam os requisitos da política para famílias. À pergunta "a app pode atrair crianças sem querer?", responde "Não".
- **Segurança dos dados**:
  - "A app recolhe ou partilha algum dos tipos de dados de utilizador obrigatórios?" → **Não**.
  - A localização é tratada só no telemóvel e nunca é enviada.
  - Os pedidos ao GitHub (dados novos) e à plataforma da GIRA não levam dados pessoais nem identificadores. Levam apenas o endereço IP, como qualquer ligação. As ligações usam HTTPS.
  - Sem contas, não há pedido para apagar uma conta.
- **ID de publicidade**: a app não o usa; não declara a permissão `AD_ID`.
- **App governamental**: não.
- **Funcionalidades financeiras**: nenhuma.
- **Saúde**: nenhuma das funcionalidades de saúde.
- **App de notícias**: não.
- **Permissões de serviço em primeiro plano**:
  - Tipo: **localização**.
  - Caso de utilização: **navegação**.
  - Descrição:

    ```
    Quando o utilizador carrega em "Iniciar" numa rota, a LisBora guia-o passo a passo (no ecrã, por voz e numa notificação sempre visível). Quando carrega em "Começar" numa volta sem rota ("Pedalar"), regista a distância e o tempo para o resumo no fim, e numa bicicleta GIRA avisa antes do fim dos 45 minutos. O serviço em primeiro plano do tipo localização mantém a navegação e as indicações por voz com o ecrã desligado ou com outra app à frente, para o ciclista não ter de olhar para o telemóvel. Começa só por ação do utilizador e termina quando ele chega ao destino ou carrega em "Terminar" (na app ou na notificação). A localização é tratada só no telemóvel. A app não pede localização em segundo plano.
    ```

  - Vídeo: um endereço do YouTube (pode ser "Não listado") com o vídeo `docs/play/video-servico-primeiro-plano.mp4`.
