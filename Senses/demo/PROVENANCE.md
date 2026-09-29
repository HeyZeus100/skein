# Provenance — Kavya's workshop demo

| Field | Value |
|---|---|
| Original filename | `offline-agents-main.zip` |
| Workshop URL | http://tinyurl.com/offline-agents |
| Resolved | https://github.com/KavyaSriChennoju/offline-agents/archive/refs/heads/main.zip |
| Repository | https://github.com/KavyaSriChennoju/offline-agents |
| Commit | `cb1a677baafd0146140339dc8f222da67b1c2b29` |
| Retrieved | 2026-09-29 |
| License | none in repo |

Rules: `original/` is read-only and never edited. All changes happen in `workshop/`.

## SHA-256 (original)

```
93e6c8804a4f3bb0922141df8aca328fa79170c578b5bbafeec3f0afdca03409  offline-agents-main.zip
b1e646be1f9000431a877d12cd7982b1d43ca22eca794f484fc31ba8059a62e2  ./.gitignore
7b55f8e67b5623c4bef3fa691288da9437d79d3aba156de48d481db32ac7d16d  ./.python-version
b6a1312be2a470bbabfb1b1261de699e70925d3deca0f166ec9577ab2b4f7688  ./1_setup.ipynb
33fdd4b80d879ad53cfa5c85a77f6c7c427de728e1ceaf7edc16b38cdf43d283  ./2_eyes.ipynb
07dcea4e4bc5b600f990956b5cf1606687dc106fe372739562f10f76f35af7ac  ./3_ears.ipynb
a5ff5bf702b6b950f896805fbca8fcababa8bc3d1b046fbbb9c769d70af2b7b5  ./4_agent.ipynb
0cdcc108963dcd81de74843b6b8c969bd111907ec2e8a7e482909b0e8cda55d0  ./5_omni.ipynb
4c4f727d8fa5dd9c29c2a03e25b69a04e1ad233960145b7a97a33feb60f31f3c  ./6_hands.ipynb
dd07e548cde076e26bc3da5c9bca80e2b5aea16be09d7476693afb66da14c676  ./agent/__init__.py
2e38cbd35a45a51d6af183a53e923f1942b0376b25220060880d6e28ab3c9974  ./agent/arm.py
14eccb47356fad269503568141d9ba91c0d45198b07009167326cc554482c6e6  ./agent/brain.py
4ddf7dda00ecb0f0694cb3ea2230d8993dfdbabc86917e23d21fce341f9d1f96  ./agent/ears.py
f0b14e9092ccf1ead410460d985e7ae474e7fe872b70c5fee501dbf4392c8709  ./agent/eyes.py
ea8c640b547dbcad08a6f168e254152203d34fffcc9ca13e384f127636a5aa77  ./agent/llm.py
2c40630b673b50e3cb755945442a5927e84f9bffcdaaaf609df49f84d730f455  ./agent/mouth.py
96e6bf5b204dae1dc2be3d05c34559d2725f926a813cea06bfd39f3e6577d33f  ./agent/nb.py
84b0acf8f047afbac269e5ac7c8b3ff668f2ee8b540fb053bd9ac78959720c97  ./agent/server.py
f85574e415d4d4ef4e1c9d8e2aeb41661deaaf563e5da618e102f8caeba99e37  ./agent/term.py
2fc1d45d570cb3e8b53c1422bad8cfbbb8abc2c23aa341c5ed522e266e9b3ecb  ./config.py
aec0702dc6fc578391c98b7b69d50af520405248b16cec99946e49bf05a0c4ec  ./hands_viewer.py
ae2b57cca94ca370ab97e4f863adb3fb27d84e54f976959e61328cfdd63437cb  ./INSTALL.md
f1e05c2824424a9b7b43f399e5909ab54301092a9d63e400fa393abb7806af98  ./modes.py
8927a14c3246ecaf438e30c70f76651d0be3bcf0f9c8fe2aa2d787b9d2a4ddcc  ./pyproject.toml
5cc5d97ae18085d43f4d8afcd52014d83c714398808e3431796c844156fe71b4  ./README.md
2a77e70ae90b139e6f82a1f2a0110543daf8ddff40996b96347a515e1ddc2961  ./requirements.txt
90c0a69101f30b03daf1287c77f152118f5e31558a80a49a86063472cc26184e  ./samples/card_blue.png
7037eca6bb43c0ee942a7250a1d66da9549e82daa486545dcb62514869819c49  ./samples/card_green.png
d3eec6d97e2a1950d96525441e6835ddbd124b77696d7169c98e78fe3b64813f  ./samples/card_red.png
a2b3d189bbd73d7626d1daad3109c17849bed772ad1227dd4c751d105e9e124d  ./samples/test_card.png
d90c8bf6b51d0752424e9591d02d64214024dedd2efb73aa6a79ca6b1c3d950c  ./tests/fake_bin/llama-server
2ea2f6f268f4f1c2aac6c81a0a6e54540d82748d42a74566b8988928e696d096  ./tests/fake_llama_server.py
dab43b2efd7a9e317c10d4605f34fdb8526fb04670756aaae5db87475c844c76  ./tests/run_notebooks.py
9ed60ff719a4fc5b016737e32b45fbcf44fb18107a918bbc9c3816d879535ce3  ./tests/test_arm.py
22da8e1ded2e722e08303098972d0dec434eeb9a1504c11784d965187e661597  ./tests/test_server.py
e4ebeb7dd6f408e2328b694b9f73a77b846b21efb888b4352490762ba2ddd253  ./tests/test_thinking.py
```

## Not committed (license)

`offline-agents` has no LICENSE and the Skein repository is public, so neither `original/` nor `workshop/`
is committed. To recreate them on another machine:

```bash
cd Senses/demo && mkdir -p original && cd original
curl -sSL -o offline-agents-main.zip http://tinyurl.com/offline-agents
shasum -a 256 offline-agents-main.zip   # must equal the hash above (else upstream moved; pin commit cb1a677)
unzip -q offline-agents-main.zip && chmod -R a-w offline-agents-main offline-agents-main.zip
cp -R offline-agents-main/. ../workshop/ && chmod -R u+w ../workshop
chmod +x ../workshop/tests/fake_bin/llama-server        # upstream mode bug, see IMPLEMENTATION_LOG 008
cp ../workshop-uv.lock ../workshop/uv.lock && (cd ../workshop && uv sync --group dev)
```
