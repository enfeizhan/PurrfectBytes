"""YouTube metadata generation service with multi-LLM provider support."""

from src.config.settings import GEMINI_API_KEY, OPENAI_API_KEY, ANTHROPIC_API_KEY
from src.utils.logger import get_logger

logger = get_logger(__name__)

YOUTUBE_PROMPT_TEMPLATE = """STRICT OPERATING MODE: Generate the requested content and immediately stop. Do not include any conversational filler, follow-up suggestions, or questions. Any text following the hashtags is a violation of this instruction.

You are a YouTube content creator helping generate titles and descriptions for language learning videos. The videos feature a sentence with synchronized audio and character-by-character highlighting for pronunciation practice.

IMPORTANT RULES:

ALL explanations, descriptions, breakdowns, and grammar points MUST be written in English, regardless of the target sentence language.

NEVER ask follow-up questions - generate the complete output immediately based on the given sentence.

Use SINGLE asterisks (text) for bold formatting, never double asterisks.

Identify the language automatically.

CRITICAL: Keep the title under 100 characters (strict limit).

Provide accurate romanization (if applicable: Japanese→Romaji, Korean→Romanization, Chinese→Pinyin, etc.).

TRANSLATION RULE: If the target sentence is NOT English, you MUST include the "English Translation" section. If the target sentence IS English, you MUST DELETE the "English Translation" section entirely.

MANDATORY FORMATTING for Breakdowns/Grammar: You must start with the [Original Script], followed by the [Romanization or IPA Phonetics] in parentheses, then the English meaning.

Example for English Breakdown: Word (IPA Phonetics) = English Meaning.

Break down the sentence into meaningful components (explanations in English).

Highlight 2-4 key grammar points (explanations in English).

Match the proficiency level appropriately (beginner/intermediate/advanced).

Use natural, encouraging tone.

Include relevant hashtags for the specific language.

Terminate the response immediately after the final hashtag. Do not include any text, sign-offs, or questions after the hashtags.

Given a target sentence, generate:

TITLE (following this format - MUST be under 100 characters, but don't output TITLE):

My Study Journal: [LANGUAGE] Sentence - "[TARGET_SENTENCE]" | Reading & Pronunciation

DESCRIPTION with these sections (don't output DESCRIPTION):

📚 Study Journal Entry

[Brief intro about learning this sentence today - MUST be in English]

📝 Today's Sentence:

[TARGET_SENTENCE in original language]

([Romanization/IPA if applicable])

📖 English Translation:[ONLY include this section if the target language is NOT English. If English, remove this entire section]

"[Translation in English]"

🔤 Breakdown:

• [Original Script] ([Romanization/IPA]) = [Meaning in English]

• [Original Script] ([Romanization/IPA]) = [Meaning in English]

📚 Grammar Points:

• [Original Script] ([Romanization/IPA]) - [Explanation in English]

• [Original Script] ([Romanization/IPA]) - [Explanation in English]

🎯 Perfect for:

• [Proficiency level] learners

• [Learning goal 1]

• [Learning goal 2]

💡 Study Tip:

[Helpful context or usage note about this sentence - in English]

📌 Credit:

This sentence is sourced from another creator's content. All credit goes to the original author.

👍 Enjoyed this study session? Please give it a thumbs up!

🔔 Subscribe to follow my language learning journey and practice together!

☕ Want to support more learning content? Scan the QR code (bottom-left corner)—my cat thanks you! 😺

#[LanguageLearning] #[NativeLanguageName] #Learn[Language] #[Language]Language #[NativeStudyHashtag] #[ProficiencyTest] #[Language]Practice #Study[Language] #[Language]Grammar #LanguageLearning

Final Output Check: Ensure the last sentence of the response is not a question.

TARGET SENTENCE: {sentence}"""


class YouTubeMetadataService:
    """Service for generating YouTube titles and descriptions using LLMs."""

    AVAILABLE_PROVIDERS = ["gemini", "openai", "anthropic"]

    def generate(self, sentence: str, provider: str = "gemini") -> dict:
        """
        Generate YouTube title and description for a language learning video.

        Args:
            sentence: The target sentence to generate metadata for
            provider: LLM provider to use (gemini, openai, anthropic)

        Returns:
            dict with 'title' and 'description' keys
        """
        if not sentence or not sentence.strip():
            raise ValueError("No sentence provided")

        provider = provider.lower()
        if provider not in self.AVAILABLE_PROVIDERS:
            raise ValueError(f"Unknown provider: {provider}. Available: {', '.join(self.AVAILABLE_PROVIDERS)}")

        prompt = YOUTUBE_PROMPT_TEMPLATE.format(sentence=sentence.strip())

        logger.info(f"Generating YouTube metadata with {provider} for: {sentence[:50]}...")

        if provider == "gemini":
            raw_text = self._generate_gemini(prompt)
        elif provider == "openai":
            raw_text = self._generate_openai(prompt)
        elif provider == "anthropic":
            raw_text = self._generate_anthropic(prompt)
        else:
            raise ValueError(f"Provider {provider} not implemented")

        return self._parse_response(raw_text)

    def get_available_providers(self) -> list[dict]:
        """Return list of available providers and their status."""
        return [
            {
                "id": "gemini",
                "name": "Google Gemini",
                "available": bool(GEMINI_API_KEY),
                "model": "gemini-2.5-flash",
            },
            {
                "id": "openai",
                "name": "OpenAI",
                "available": bool(OPENAI_API_KEY),
                "model": "gpt-4o-mini",
            },
            {
                "id": "anthropic",
                "name": "Anthropic Claude",
                "available": bool(ANTHROPIC_API_KEY),
                "model": "claude-sonnet-4-20250514",
            },
        ]

    def _generate_gemini(self, prompt: str) -> str:
        """Generate using Google Gemini."""
        if not GEMINI_API_KEY:
            raise ValueError("GEMINI_API_KEY environment variable is not set")

        from google import genai

        client = genai.Client(api_key=GEMINI_API_KEY)
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=prompt,
        )
        return response.text

    def _generate_openai(self, prompt: str) -> str:
        """Generate using OpenAI."""
        if not OPENAI_API_KEY:
            raise ValueError("OPENAI_API_KEY environment variable is not set")

        import openai

        client = openai.OpenAI(api_key=OPENAI_API_KEY)
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[{"role": "user", "content": prompt}],
            temperature=0.7,
        )
        return response.choices[0].message.content

    def _generate_anthropic(self, prompt: str) -> str:
        """Generate using Anthropic Claude."""
        if not ANTHROPIC_API_KEY:
            raise ValueError("ANTHROPIC_API_KEY environment variable is not set")

        import anthropic

        client = anthropic.Anthropic(api_key=ANTHROPIC_API_KEY)
        response = client.messages.create(
            model="claude-sonnet-4-20250514",
            max_tokens=2000,
            messages=[{"role": "user", "content": prompt}],
        )
        return response.content[0].text

    def _parse_response(self, raw_text: str) -> dict:
        """
        Parse LLM response into title and description.

        The first non-empty line is treated as the title,
        everything else is the description.
        """
        lines = raw_text.strip().split("\n")

        # Find first non-empty line as title
        title = ""
        description_start = 0
        for i, line in enumerate(lines):
            stripped = line.strip()
            if stripped:
                title = stripped
                description_start = i + 1
                break

        # Rest is description
        description = "\n".join(lines[description_start:]).strip()

        # Clean up title — remove any "TITLE:" prefix the LLM might add
        for prefix in ["TITLE:", "Title:", "title:"]:
            if title.startswith(prefix):
                title = title[len(prefix):].strip()

        # Explicitly reconstruct to guarantee formatting: "My Study Journal: <Language> Sentence - <sentence> | Reading & Pronunciation"
        import re
        
        # 1. Extract Language
        language = "Language"
        lang_match = re.search(r'My Study Journal:\s*([a-zA-Z\s]+?)(?:\s*Sentence)?\s*-', title, re.IGNORECASE)
        if lang_match:
            language = lang_match.group(1).strip()
            # Clean up if the optional non-greedy match accidentally kept "Sentence"
            if language.lower().endswith(" sentence"):
                language = language[:-9].strip()

        # 2. Extract Target Sentence
        sentence = "..."
        sentence_match = re.search(r'"(.*?)"', title)
        if sentence_match:
            sentence = sentence_match.group(1).strip()
        else:
            # Fallback if the LLM forgot the quotation marks
            fallback_match = re.search(r'-\s*(.*?)\s*\|', title)
            if fallback_match:
                sentence = fallback_match.group(1).strip()

        # 3. Create the exact perfect string parts
        prefix = f'My Study Journal: {language} Sentence - "'
        suffix = '" | Reading & Pronunciation'

        # 4. Truncate target sentence safely to guarantee ≤100 chars
        if len(prefix) + len(sentence) + len(suffix) > 100:
            allowed_len = 100 - len(prefix) - len(suffix) - 3  # minus 3 for the '...'
            if allowed_len > 0:
                sentence = sentence[:allowed_len].strip() + "..."
                title = f'{prefix}{sentence}{suffix}'
            else:
                # Fallback if somehow the language name itself is insanely long
                title = f'{prefix}{sentence}{suffix}'[:97] + "..."
        else:
            title = f'{prefix}{sentence}{suffix}'

        return {
            "title": title,
            "description": description,
        }
