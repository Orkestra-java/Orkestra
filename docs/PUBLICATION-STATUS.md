# Estado da publicação no GitHub

Registro de continuidade em 12 de outubro de 2026. Este documento descreve
o estado observado nessa sessão; não confirma o estado atual do servidor.

## Destino e autorização

- Organização confirmada pelo usuário: `Orkestra-java`.
- Repositório: <https://github.com/Orkestra-java/Orkestra>.
- O usuário informou ter criado o repositório pela interface do GitHub.
- O usuário autorizou enviar o projeto completo. Inicialmente pediu uma branch
  separada, `chore/initial-import`; depois forneceu comandos para publicar em
  `main`. A última tentativa usou `main`.
- Não foi possível verificar a visibilidade ou o conteúdo remoto.

## Pasta principal e cópia temporária

O projeto principal está em `/home/matheus/Workspace/Projects/Orkestra`.
Neste ambiente do agente, sua pasta `.git` apareceu vazia e somente para leitura.
`git init` falhou com `Read-only file system`, e comandos Git na pasta principal
falharam com `not a git repository`. Essa restrição é do ambiente observado;
não foi comprovado que também se aplica ao terminal pessoal do usuário.

Para preparar os commits, foi criada uma cópia em
`/tmp/orkestra-publish-rq7ue5u_`, com um repositório Git funcional e `origin`
apontando para o destino acima. A cópia exclui metadados do ambiente,
arquivos de IDE, caches Python e diretórios de build `target`.

A pedido do usuário, essa cópia foi removida e depois recriada a partir da pasta
principal. Na recriação, foram comparados por SHA-256 os 121 arquivos copiados:
todos eram idênticos aos da pasta principal. Este registro e seu link no índice
foram adicionados depois dessa comparação e não pertencem ao commit abaixo.

## Commits e branches

- `2e05994` — `Publish Orkestra library`: primeiro commit preparado na cópia.
- `12782f1` — `Add repository publication script`: inclusão do script na `main`.
- Esses dois commits foram apagados quando a cópia foi removida. A recriação
  não recuperou esse histórico.
- `ffbd485` — `Publish Orkestra library`: novo commit com os arquivos atuais,
  incluindo `scripts/publish.py`, na cópia recriada.
- Antes de escrever este registro, a cópia estava limpa, na branch `main`.
  A branch `chore/initial-import` também foi criada no mesmo commit.
- Nenhum push foi confirmado como bem-sucedido nesta sessão.

## Bloqueios observados

As tentativas feitas pelo agente falharam com
`Could not resolve host: github.com` ou erro de conexão com `api.github.com`.

O terminal do usuário conseguiu chegar ao GitHub, mas o servidor recusou o
push porque o token OAuth não tinha o escopo `workflow`, necessário para enviar
`.github/workflows/benchmarks.yml`. O projeto também contém `build.yml`.
Não foram removidos os workflows para contornar essa exigência.

O usuário concluiu `gh auth login` como `MatheusJFA`. Uma tentativa posterior
de `gh auth refresh -h github.com -s workflow` foi abortada pelo usuário;
a concessão desse escopo ainda não foi confirmada.

## Script de publicação

`scripts/publish.py` foi criado na pasta principal e incluído no commit
`ffbd485`. Ele prepara o Git local, faz commit das alterações e envia a branch.
Se o push indicar falta do escopo `workflow`, inicia a renovação de autorização
e tenta novamente.

O padrão do script é `chore/initial-import`; ele recusa `main` e `master`.
Portanto, para a última intenção de publicar em `main`, use os comandos Git
abaixo. O script tem uma referência explícita à cópia temporária para recuperar
seus metadados quando `.git` não existe. Uma `.git` vazia existente, como a
observada no ambiente do agente, impede essa recuperação automática.

Apenas `python3 scripts/publish.py --check` foi executado com sucesso. Isso
verifica pré-requisitos locais; não comprova autenticação, compilação ou push.
O preparo da publicação não executou novamente os testes Java.

## Próximos passos no terminal do usuário

Autorizar o escopo necessário e enviar a cópia preparada:

```bash
gh auth refresh -h github.com -s workflow
git -C /tmp/orkestra-publish-rq7ue5u_ push -u origin main
```

Antes de enviar, copiar também este registro e a atualização de `docs/README.md`
para a cópia temporária e criar um commit, caso se deseje publicá-los.

O objetivo posterior é manter o Git na pasta principal. Verifique primeiro
as permissões de `.git` no terminal pessoal. Preserve ou recupere o histórico
da cópia antes de removê-la; `/tmp` não é armazenamento permanente.
Se o envio remoto for confirmado, um clone em uma nova pasta permanente permite
recuperar o histórico sem sobrescrever os arquivos locais. Compare e transfira
as mudanças locais antes de substituir a pasta principal.

Não execute `git pull` na pasta principal enquanto ela não for um repositório
Git válido. Não remova novamente a cópia temporária antes de preservar os
commits que ainda não foram publicados.
