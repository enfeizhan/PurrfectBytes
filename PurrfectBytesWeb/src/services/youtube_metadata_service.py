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

Provide accurate phonetics (if applicable: Japanese→Hiragana, Korean→Romanization, Chinese→Pinyin, etc.). For Japanese, ALWAYS use Hiragana for pronunciations instead of Romaji.

TRANSLATION RULE: If the target sentence is NOT English, you MUST include the "English Translation" section. If the target sentence IS English, you MUST DELETE the "English Translation" section entirely.

MANDATORY FORMATTING for Breakdowns/Grammar: You must start with the [Original Script], followed by the [Phonetics/Hiragana/Romanization/IPA] in parentheses, then the English meaning.

Example for English Breakdown: Word (IPA Phonetics) = English Meaning.

Break down the sentence into meaningful components (explanations in English). Ignore overly simple or basic words that even absolute beginners would already know. Focus on the core vocabulary and phrases.

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

([Phonetics/Hiragana/Romanization/IPA if applicable])

📖 English Translation:[ONLY include this section if the target language is NOT English. If English, remove this entire section]

"[Translation in English]"

🔤 Breakdown:

• [Original Script] ([Phonetics/Hiragana/Romanization/IPA]) = [Meaning in English]

• [Original Script] ([Phonetics/Hiragana/Romanization/IPA]) = [Meaning in English]

📚 Grammar Points:

• [Original Script] ([Phonetics/Hiragana/Romanization/IPA]) - [Explanation in English]

• [Original Script] ([Phonetics/Hiragana/Romanization/IPA]) - [Explanation in English]

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

        return self._parse_response(raw_text, sentence.strip())

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

    def _parse_response(self, raw_text: str, original_sentence: str) -> dict:
        """
        Parse LLM response into title and description.
        """
        import re

        lines = raw_text.strip().split("\n")

        # Find first non-empty line as potential title
        raw_title = ""
        description_start = 0
        for i, line in enumerate(lines):
            stripped = line.strip()
            if stripped:
                raw_title = stripped
                description_start = i + 1
                break

        # Rest is description - also strip any leading "DESCRIPTION:" label
        description = "\n".join(lines[description_start:]).strip()
        # Remove leading "DESCRIPTION:" label if the LLM included it
        description = re.sub(r'^DESCRIPTION:\s*\n?', '', description, flags=re.IGNORECASE).strip()

        # Clean up title (remove any "TITLE:" prefix, markdown, etc.)
        title = raw_title
        for prefix in ["TITLE:", "Title:", "title:"]:
            if title.lower().startswith(prefix.lower()):
                title = title[len(prefix):].strip()
        
        # Strip markdown bold markers if present
        title = title.strip().replace("**", "").replace("__", "").strip()

        # Try to extract language and sentence from the title line
        # Use a greedy regex for the quoted sentence to handle apostrophes like "I'll"
        found_language = None
        found_sentence = None

        # Try full pattern match first: My Study Journal: <Language> Sentence - "<sentence>" | ...
        full_match = re.search(
            r'My Study Journal:\s*([a-zA-Z]+)\s+Sentence\s*-\s*["\u201c](.*?)["\u201d]\s*\|',
            title, re.IGNORECASE
        )
        if full_match:
            found_language = full_match.group(1).strip()
            found_sentence = full_match.group(2).strip()
        else:
            # Try language extraction alone
            lang_match = re.search(r'My Study Journal:\s*([a-zA-Z]+)\s+Sentence\s*-', title, re.IGNORECASE)
            if lang_match:
                found_language = lang_match.group(1).strip()

            # Try sentence extraction: get content between the LAST pair of quotes
            # This handles apostrophes like "I'll tee up..."
            quote_matches = re.findall(r'["\u201c](.*?)["\u201d]', title)
            if quote_matches:
                found_sentence = quote_matches[-1].strip() if quote_matches[-1].strip() else None
            
            if not found_sentence:
                # Fallback: content between - and |
                fallback_match = re.search(r'-\s*(.*?)\s*\|', title)
                if fallback_match:
                    found_sentence = fallback_match.group(1).strip().strip('"').strip('\u201c').strip('\u201d').strip()

        # Reconstruct to guarantee format and 100-char limit
        if found_language or found_sentence:
            language_to_use = found_language or "Language"
            sentence_to_use = found_sentence or original_sentence[:50]
            
            prefix = f'My Study Journal: {language_to_use} Sentence - "'
            suffix = '" | Reading & Pronunciation'

            if len(prefix) + len(sentence_to_use) + len(suffix) > 100:
                allowed_len = 100 - len(prefix) - len(suffix) - 3
                if allowed_len > 0:
                    sentence_to_use = sentence_to_use[:allowed_len].strip() + "..."
                    final_title = f'{prefix}{sentence_to_use}{suffix}'
                else:
                    final_title = (prefix + sentence_to_use + suffix)[:97] + "..."
            else:
                final_title = f'{prefix}{sentence_to_use}{suffix}'
            
            return {
                "title": final_title,
                "description": description,
            }
        
        # Total fallback: use the raw title line as-is
        final_title = title if len(title) <= 100 else title[:97] + "..."
        return {
            "title": final_title,
            "description": description,
        }
