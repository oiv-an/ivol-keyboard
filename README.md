# IVOL Keyboard

Клавиатура для Android с офлайн-распознаванием речи (GigaAM от Сбера) и офлайн-переводом.
Основана на клавиатуре Android Open Source Project (LatinIME), Apache-2.0.

Только русский и английский. Всё распознавание и перевод работают на самом телефоне,
без отправки текста и звука на сервер.

## Возможности

- **Голосовой ввод офлайн.** Отдельная кнопка микрофона справа от пробела: нажал, держишь,
  говоришь, отпустил. Модель [GigaAM v3](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16)
  (Сбер) запускается через [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Текст пишется
  по ходу речи, со знаками препинания.
- **Перевод офлайн.** Кнопка 文A переводит выделенный текст или всё поле целиком, направление
  определяется автоматически (кириллица → английский, иначе → русский). Повторное нажатие
  возвращает исходный текст. Модели Google ML Kit скачиваются один раз.
- **Буфер обмена.** Последние 10 скопированных фрагментов, нажатие вставляет, долгое
  нажатие удаляет.
- **Подсказки слов** для русского и английского, исправление опечаток, запоминание часто
  используемых слов.
- **Настройки внешнего вида** с живым предпросмотром: высота клавиатуры, ширина клавиш,
  скругление углов, вибрация и её сила.
- Тёмная тема, подсказки символов на клавишах, всплывающие меню по долгому нажатию, эмодзи.

## Сборка

Нужны JDK 17, Android SDK (API 36), NDK 28 и CMake.

```sh
# 1. Скачать AAR библиотеки sherpa-onnx (52 МБ, в репозиторий не входит)
./tools/fetch-sherpa-onnx.sh

# 2. Указать путь к Android SDK
echo "sdk.dir=/путь/к/android-sdk" > local.properties

# 3. Собрать
./gradlew assembleDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

## Установка

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Затем включи клавиатуру в настройках Android («Язык и ввод» → «Экранная клавиатура»)
и выбери её как основную. Через adb это же делается так:

```sh
adb shell ime enable pro.ivol.keyboard.debug/com.android.inputmethod.latin.LatinIME
adb shell ime set pro.ivol.keyboard.debug/com.android.inputmethod.latin.LatinIME
```

## Модель распознавания речи

Модель (около 230 МБ) не входит в APK. При первом нажатии на микрофон приложение скачивает
её в свою папку `files/gigaam/` и дальше работает без интернета. Можно положить файлы
заранее вручную:

```sh
# файлы encoder.int8.onnx, decoder.onnx, joiner.onnx, tokens.txt из репозитория модели
MODEL=sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16
adb push "$MODEL" /data/local/tmp/
adb shell run-as pro.ivol.keyboard.debug sh -c \
  "mkdir -p files/gigaam && cp -r /data/local/tmp/$MODEL files/gigaam/ && echo ok > files/gigaam/$MODEL/.installed"
```

Для микрофона нужно разрешение на запись звука, клавиатура попросит его сама.

## Что изменено по сравнению с AOSP

Основной код клавиатуры взят из AOSP LatinIME. Свой код лежит в
`app/src/main/java/com/android/inputmethod/latin/ivol/`:

| Файл                                           | Назначение                                   |
| ---------------------------------------------- | -------------------------------------------- |
| `GigaAmVoice.kt`                               | загрузка модели, запись звука, распознавание |
| `IvolTranslator.kt`                            | перевод через ML Kit                         |
| `ClipboardHistory.kt`, `ClipboardPanelView.kt` | история буфера обмена                        |
| `IvolSettingsActivity.kt`, `IvolPrefs.kt`      | экран настроек и его значения                |
| `MicPermissionActivity.kt`                     | запрос разрешения на микрофон                |

Остальные правки в файлах AOSP помечены комментарием `IVOL:`. Внешний вид задан в
`res/values/colors-ivol.xml`, `dimens-ivol.xml` и `res/drawable/ivol_*`.

## Лицензии

- Код проекта и AOSP LatinIME — Apache License 2.0, см. [LICENSE](LICENSE) и [NOTICE](NOTICE).
- sherpa-onnx — Apache License 2.0.
- Модель GigaAM — лицензия владельца модели (Сбер), см. страницу модели.
- Словари `app/src/main/res/raw/main_ru.dict` и `main_en.dict` — из AOSP, включают словари
  Lexiteria LLC, используемые по разрешению (см. `NOTICE`).
