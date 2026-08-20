import {
  Component,
  CUSTOM_ELEMENTS_SCHEMA,
  ElementRef,
  HostListener,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { AuthService } from './auth.service';
import { ThemeService, Theme } from './theme.service';
import {
  MCP_CHAT_URL,
  MCP_APPROVE_URL,
  MCP_ELICITATION_DECISION_URL,
} from './config';

interface PendingApproval {
  id: string;
  tool: string;
  description: string;
  arguments: string;
}

/** A pending temperature-unit elicitation awaiting the user's choice. */
interface PendingElicitation {
  id: string;
  message: string;
  options: string[];
}

/**
 * Subset of the deep-chat custom-handler "signals" API that we drive manually
 * so we can stream chunks and surface tool-approval prompts.
 */
interface DeepChatSignals {
  onOpen: () => void;
  onClose: () => void;
  onResponse: (response: { text?: string; error?: string }) => void;
}

/** A single event pushed by the mcp-client streaming endpoint. */
interface StreamEvent {
  type: 'chunk' | 'status' | 'approval' | 'elicitation' | 'error';
  text?: string;
  message?: string;
  id?: string;
  tool?: string;
  description?: string;
  arguments?: string;
  options?: string[];
}

@Component({
  selector: 'app-root',
  imports: [],
  templateUrl: './app.html',
  styleUrl: './app.css',
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
})
export class App {
  protected readonly auth = inject(AuthService);
  protected readonly theme = inject(ThemeService);

  /** Whether the user dropdown menu is open. */
  protected readonly menuOpen = signal(false);
  /** Whether the theme dropdown menu is open. */
  protected readonly themeMenuOpen = signal(false);

  /** The tool call currently awaiting the user's approval (or null). */
  protected readonly pendingApproval = signal<PendingApproval | null>(null);

  /** The temperature-unit elicitation currently awaiting the user's choice (or null). */
  protected readonly pendingElicitation = signal<PendingElicitation | null>(null);

  /** Reference to the deep-chat host element, used to fill its text input. */
  private readonly chatRef = viewChild<ElementRef<HTMLElement>>('chatRef');

  /**
   * Identifies this chat session to the mcp-client backend so it can keep
   * conversation history (see ChatController/ChatMemory) across multiple
   * messages. Generated once when the app loads; a page reload starts a
   * fresh conversation.
   */
  private readonly conversationId = crypto.randomUUID();

  /** Sample questions the user can pick to fill the message input. */
  protected readonly sampleQuestions: string[] = [
    "Which movie is Kai's favorite quote from?",
    "What's the weather in Munich?",
    "What's the weather in Munich in Fahrenheit?",
    'Which movies of director Christopher Nolan are in the IMDB top 5?',
    'Show me the JSON of the mcp server token.',
  ];

  /** Pretty-printed arguments for the approval dialog. */
  protected readonly prettyArgs = computed(() => {
    const args = this.pendingApproval()?.arguments;
    if (!args) {
      return '';
    }
    try {
      return JSON.stringify(JSON.parse(args), null, 2);
    } catch {
      return args;
    }
  });

  /**
   * deep-chat connection: a custom handler that drives the SSE stream so we can
   * intercept tool-approval events and show a confirmation dialog.
   */
  protected readonly connectConfig = computed(() => {
    // Depend on the token so the config refreshes after login.
    this.auth.accessToken();
    return {
      handler: (body: unknown, signals: DeepChatSignals) => this.handleChat(body, signals),
      stream: true,
    };
  });

  protected readonly introMessage = {
    text: 'Hi! I am your MCP assistant. Ask me anything and I will use the available tools when needed.',
  };

  // ----- Theme-aware deep-chat styling -----

  /** Host element style: sizes the widget AND sets its background/text colour. */
  protected readonly chatStyle = computed(() => {
    const dark = this.theme.resolvedDark();
    const bg = dark ? '#24262e' : '#ffffff';
    const text = dark ? '#e8eaed' : '#1f2430';
    return `width:100%;height:100%;border:none;border-radius:0;background-color:${bg};color:${text};`;
  });

  /** Message bubble colours. */
  protected readonly messageStyles = computed(() => {
    const dark = this.theme.resolvedDark();
    return {
      default: {
        shared: { bubble: { color: dark ? '#e8eaed' : '#1f2430' } },
        ai: { bubble: { backgroundColor: dark ? '#2f323c' : '#f1f3f8' } },
        user: { bubble: { backgroundColor: '#5b78e6', color: '#ffffff' } },
      },
    };
  });

  /** Text input area colours + placeholder. */
  protected readonly textInput = computed(() => {
    const dark = this.theme.resolvedDark();
    return {
      styles: {
        container: {
          backgroundColor: dark ? '#2f323c' : '#ffffff',
          border: dark ? '1px solid #3a3d46' : '1px solid #e0e0e0',
          color: dark ? '#e8eaed' : '#1f2430',
        },
        text: { color: dark ? '#e8eaed' : '#1f2430' },
      },
      placeholder: {
        text: 'Type a message...',
        style: { color: dark ? '#9aa0aa' : '#6b7280' },
      },
    };
  });

  /** Submit button colour. */
  protected readonly submitButtonStyles = computed(() => ({
    submit: {
      container: { default: { backgroundColor: '#5b78e6' } },
      svg: { styles: { default: { filter: 'brightness(0) invert(1)' } } },
    },
  }));

  /** Scrollbar styling injected into the deep-chat shadow DOM. */
  protected readonly auxiliaryStyle = computed(() => {
    const dark = this.theme.resolvedDark();
    return `
      ::-webkit-scrollbar { width: 8px; }
      ::-webkit-scrollbar-track { background: ${dark ? '#24262e' : '#ffffff'}; }
      ::-webkit-scrollbar-thumb { background: ${dark ? '#3a3d46' : '#c9ced8'}; border-radius: 4px; }
    `;
  });

  /** First letters derived from the user name (or email as a fallback). */
  protected readonly initials = computed(() => {
    const user = this.auth.user();
    const source = user?.name?.trim() || user?.email?.trim();
    if (!source) {
      return '?';
    }
    const parts = source.split(/[\s@._-]+/).filter(Boolean);
    const letters =
      parts.length > 1 ? parts[0][0] + parts[parts.length - 1][0] : parts[0][0];
    return letters.toUpperCase();
  });

  toggleMenu(event: MouseEvent): void {
    event.stopPropagation();
    this.themeMenuOpen.set(false);
    this.menuOpen.update((open) => !open);
  }

  toggleThemeMenu(event: MouseEvent): void {
    event.stopPropagation();
    this.menuOpen.set(false);
    this.themeMenuOpen.update((open) => !open);
  }

  setTheme(theme: Theme): void {
    this.theme.set(theme);
    this.themeMenuOpen.set(false);
  }

  /** Close the menus when clicking anywhere outside of them. */
  @HostListener('document:click')
  closeMenus(): void {
    this.menuOpen.set(false);
    this.themeMenuOpen.set(false);
  }

  login(): void {
    this.auth.login();
  }

  logout(): void {
    this.menuOpen.set(false);
    this.auth.logout();
  }

  /**
   * Fills the deep-chat text input with the given text without sending it,
   * so the user can review/edit it before hitting submit. deep-chat renders
   * its editable input as a contenteditable `#text-input` div inside its
   * shadow root; there is no public API to set its text, so we write to it
   * directly and dispatch an `input` event to let deep-chat pick up the
   * change (e.g. removing the placeholder).
   */
  fillMessage(text: string): void {
    const host = this.chatRef()?.nativeElement;
    const input = host?.shadowRoot?.getElementById('text-input');
    if (!input) {
      return;
    }
    input.textContent = text;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.focus();

    // Move the caret to the end of the inserted text.
    const range = document.createRange();
    range.selectNodeContents(input);
    range.collapse(false);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
  }

  /** Combobox "change" handler: fills the input with the chosen sample question. */
  onSampleSelected(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const value = select.value;
    if (value) {
      this.fillMessage(value);
    }
    // Reset back to the placeholder so the same option can be picked again.
    select.value = '';
  }

  // ----- Chat streaming + tool approval -----

  /**
   * Wraps fetch() with a Bearer Authorization header, refreshing the access
   * token and retrying once if the backend responds 401 (e.g. the token
   * expired between requests before the background silent-refresh timer
   * fired). If the refresh itself fails, the original 401 is returned so
   * callers report it normally.
   */
  private async authFetch(url: string, init: RequestInit): Promise<Response> {
    const withAuth = (token: string): RequestInit => ({
      ...init,
      headers: { ...init.headers, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    });

    let response = await fetch(url, withAuth(this.auth.accessToken()));
    if (response.status === 401) {
      try {
        const newToken = await this.auth.refreshAccessToken();
        response = await fetch(url, withAuth(newToken));
      } catch (error) {
        console.error('Failed to refresh access token', error);
      }
    }
    return response;
  }

  /**
   * deep-chat custom handler: POST the conversation to the mcp-client streaming
   * endpoint and read the Server-Sent-Events response. Each event is a JSON
   * object; tool-approval events pop up the confirmation dialog while chunk and
   * status events are appended to the streamed message.
   */
  private async handleChat(body: unknown, signals: DeepChatSignals): Promise<void> {
    try {
      signals.onOpen();

      const response = await this.authFetch(MCP_CHAT_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...(body as object), conversationId: this.conversationId }),
      });

      if (!response.ok || !response.body) {
        signals.onResponse({ error: `Request failed (${response.status})` });
        signals.onClose();
        return;
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });

        // SSE frames are separated by a blank line.
        const frames = buffer.split('\n\n');
        buffer = frames.pop() ?? '';
        for (const frame of frames) {
          this.dispatchFrame(frame, signals);
        }
      }

      // Flush any trailing frame left in the buffer.
      if (buffer.trim()) {
        this.dispatchFrame(buffer, signals);
      }

      signals.onClose();
    } catch (error) {
      signals.onResponse({ error: error instanceof Error ? error.message : String(error) });
      signals.onClose();
    }
  }

  /** Parse a single SSE frame ("data: {json}") and act on its event type. */
  private dispatchFrame(frame: string, signals: DeepChatSignals): void {
    const dataLine = frame
      .split('\n')
      .map((line) => line.trim())
      .find((line) => line.startsWith('data:'));
    if (!dataLine) {
      return;
    }
    const json = dataLine.slice('data:'.length).trim();
    if (!json) {
      return;
    }

    let evt: StreamEvent;
    try {
      evt = JSON.parse(json) as StreamEvent;
    } catch {
      return;
    }

    switch (evt.type) {
      case 'chunk':
      case 'status':
        if (evt.text) {
          signals.onResponse({ text: evt.text });
        }
        break;
      case 'approval':
        this.pendingApproval.set({
          id: evt.id ?? '',
          tool: evt.tool ?? 'unknown tool',
          description: evt.description ?? '',
          arguments: evt.arguments ?? '{}',
        });
        break;
      case 'elicitation':
        this.pendingElicitation.set({
          id: evt.id ?? '',
          message: evt.message ?? '',
          options: evt.options ?? [],
        });
        break;
      case 'error':
        signals.onResponse({ error: evt.message ?? 'Unexpected error' });
        break;
    }
  }

  /** User approved the pending tool call. */
  approveTool(): void {
    this.resolveApproval(true);
  }

  /** User denied the pending tool call. */
  denyTool(): void {
    this.resolveApproval(false);
  }

  private resolveApproval(approved: boolean): void {
    const pending = this.pendingApproval();
    if (!pending) {
      return;
    }
    this.pendingApproval.set(null);

    this.authFetch(MCP_APPROVE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: pending.id, approved }),
    }).catch((error) => console.error('Failed to submit approval decision', error));
  }

  /** User picked their preferred temperature unit for the pending elicitation. */
  chooseTemperatureUnit(unit: string): void {
    const pending = this.pendingElicitation();
    if (!pending) {
      return;
    }
    this.pendingElicitation.set(null);

    this.authFetch(MCP_ELICITATION_DECISION_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: pending.id, unit }),
    }).catch((error) => console.error('Failed to submit elicitation decision', error));
  }
}
