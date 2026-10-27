# Política de privacidade da LisBora

Última atualização: 27 de outubro de 2026.

A LisBora é uma app Android de rotas de bicicleta em Lisboa. As rotas são calculadas no telemóvel. A app não tem contas, publicidade, estatísticas de utilização nem relatórios de erros, e não vende nem partilha dados de ninguém.

## Localização

A app usa a localização do telemóvel (precisa ou aproximada, conforme a permissão que deres) para:

- mostrar onde estás no mapa;
- calcular rotas e passeios a partir de onde estás;
- seguir uma rota: indicações, recálculo quando sais dela e resumo no fim;
- registar uma volta sem rota ("Pedalar"), para o resumo no fim: distância, tempo, velocidade média e subida.

A localização é tratada só no telemóvel. Nunca é enviada para nenhum servidor, nem guardada depois de a viagem acabar. Durante a navegação e numa volta sem rota, a app continua a ler o GPS com o ecrã desligado através de um serviço em primeiro plano, sempre com uma notificação visível. A app não pede acesso à localização em segundo plano.

## O que fica guardado no telemóvel

- As preferências de rota e o teu ritmo pessoal, que a app aprende com as voltas que terminas.
- Casa, Trabalho, os locais favoritos, as pesquisas recentes e as rotas guardadas no Descobrir.
- Os dados do mapa: rede ciclável, ruas, índice de pesquisa e estações GIRA.

O percurso de uma volta só fica na memória até fechares o resumo. Só passa a ficar num ficheiro GPX se o exportares, para o sítio que escolheres.

Para apagar estes dados, apaga os favoritos e as rotas na app, ou limpa os dados da app nas definições do Android, ou desinstala-a.

Se tiveres a cópia de segurança do Android ligada, as preferências, os favoritos e as rotas guardadas podem ir na cópia de segurança da tua conta Google, segundo as regras dessa cópia. Os dados do mapa não vão.

## Ligações à Internet

A app funciona sem Internet. Liga-se apenas a dois serviços, e não envia dados pessoais a nenhum deles:

1. **Atualização dos dados (GitHub).** Uma vez por dia, em Wi-Fi, e quando carregas em "Procurar dados novos", a app descarrega o pacote de dados publicado no repositório da LisBora no GitHub (`github.com` e os servidores de ficheiros do GitHub). O pedido leva o endereço IP do telemóvel, como qualquer ligação à Internet, e a versão da app ("LisBora/0.1.0"). O GitHub trata esses dados segundo a sua [declaração de privacidade](https://docs.github.com/pt/site-policy/privacy-policies/github-general-privacy-statement).
2. **Bicicletas e docas livres da GIRA.** Só no modo GIRA (Descobrir ou Pedalar com bicicleta GIRA, e a volta que daí resulta), a app pede à plataforma da GIRA a lista das estações com bicicletas e docas livres. É a mesma base de dados (Firebase, da Google) que a app oficial da GIRA usa. O pedido é igual para toda a gente: não leva a tua localização nem nenhum identificador teu. Leva apenas o endereço IP do telemóvel.

O mapa, as rotas, a pesquisa e a navegação não usam a Internet.

## Permissões

- **Localização** (precisa e aproximada): para o que está descrito acima.
- **Internet**: para os dois serviços acima.
- **Notificações**: para mostrar a próxima indicação enquanto segues uma rota, ou o que já pedalaste numa volta sem rota.
- **Serviço em primeiro plano (localização)**: para continuar a navegação, ou a volta sem rota, com o ecrã desligado.

## Contacto e alterações

Para dúvidas ou pedidos sobre privacidade, abre um *issue* em [github.com/tomasmourato/LisBora](https://github.com/tomasmourato/LisBora/issues).

As alterações a esta política são publicadas neste ficheiro. O histórico completo fica no repositório.
