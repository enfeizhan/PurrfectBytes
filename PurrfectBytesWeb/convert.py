from gtts import gTTS

tts = gTTS(
    '両替に関する質問をいくつか出してみますね。',
    lang='ja',
    slow=True
)
with open ('jp_repeat.mp3', 'wb') as f:
    for i in range(3):
        tts.write_to_fp(f)