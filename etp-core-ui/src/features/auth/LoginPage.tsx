import { useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Box,
  Building2,
  ChevronDown,
  Eye,
  EyeOff,
  FileText,
  Globe,
  Headphones,
  Hexagon,
  Layers,
  Lock,
  Radio,
  Shield,
  ShieldCheck,
  User,
} from 'lucide-react';
import { ETPLogoPlaceholder } from '@/components/branding/ETPLogoPlaceholder';
import {
  ETPButton,
  ETPCheckbox,
  ETPInput,
  useETPToast,
} from '@/components/ui';
import { iconSizes, iconStroke } from '@/lib/iconSizes';
import { useAuth } from './AuthContext';
import { validateLoginForm, type LoginFormValues } from './loginValidation';
import { sanitizeReturnTo } from './returnTo';
import { isAuthError } from './types';
import styles from './LoginPage.module.css';

const LOGIN_HERO_SRC = '/assets/etp-login-hero.png';

const PILLARS = [
  { lines: ['Capability', 'Driven'], icon: Box },
  { lines: ['API First'], icon: Hexagon },
  { lines: ['Event Driven'], icon: Radio },
  { lines: ['Composable', 'Architecture'], icon: Layers },
  { lines: ['Multi-Application', 'Platform'], icon: Building2 },
] as const;

/**
 * ETP Core UI login screen.
 *
 * Sign In uses the auth service abstraction (development mock in Step 1A).
 * Route protection and post-login redirect are Step 1B.
 */
export function LoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { showToast } = useETPToast();
  const { login } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [errors, setErrors] = useState<{ username?: string; password?: string }>({});

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    const values: LoginFormValues = { username, password, rememberMe };
    const validationErrors = validateLoginForm(values);
    setErrors(validationErrors);

    if (validationErrors.username || validationErrors.password) {
      return;
    }

    setLoading(true);
    setErrors({});

    try {
      await login({
        username: values.username,
        password: values.password,
        rememberMe: values.rememberMe,
      });
      navigate(sanitizeReturnTo(searchParams.get('returnTo')), { replace: true });
    } catch (error) {
      if (isAuthError(error)) {
        showToast(error.message, 'error');
      } else {
        showToast('An unexpected error occurred. Please try again.', 'error');
      }
    } finally {
      setLoading(false);
    }
  };

  const handleSsoClick = () => {
    showToast('Single sign-on is not configured yet.', 'info');
  };

  const handlePlaceholderAction = (label: string) => {
    showToast(`${label} will be available in a future release.`, 'info');
  };

  return (
    <div className={styles.page}>
      <aside className={styles.brandPanel} aria-label="ETP platform overview">
        <div className={styles.brandInner}>
          <ETPLogoPlaceholder />

          <div className={styles.brandStage}>
            <div className={styles.brandIntro}>
              <h1 className={styles.headline}>
                <span className={styles.headlineLine}>Compose.</span>
                <span className={styles.headlineLine}>Extend.</span>
                <span className={styles.headlineLine}>Integrate.</span>
                <span className={styles.headlineLine}>Deliver.</span>
              </h1>

              <p className={styles.supportingText}>
                A unified platform for building,
                <br />
                composing, and operating
                <br />
                enterprise capabilities and
                <br />
                applications.
              </p>
            </div>

            <div className={styles.heroWrap}>
              <img
                src={LOGIN_HERO_SRC}
                alt=""
                className={styles.heroImage}
                aria-hidden="true"
              />
            </div>
          </div>

          <div className={styles.pillars}>
            {PILLARS.map(({ lines, icon: Icon }) => (
              <div
                key={lines.join(' ')}
                className={styles.pillar}
                aria-label={lines.join(' ')}
              >
                <Icon
                  className={styles.pillarIcon}
                  strokeWidth={iconStroke}
                  aria-hidden="true"
                />
                <span className={styles.pillarLabel}>
                  {lines.map((line, index) => (
                    <span key={line}>
                      {index > 0 && <br />}
                      {line}
                    </span>
                  ))}
                </span>
              </div>
            ))}
          </div>

          <footer className={styles.brandFooter}>
            © 2026 Enterprise Technology Platform. All rights reserved.
          </footer>
        </div>
      </aside>

      <main className={styles.formPanel}>
        <div className={styles.formPanelInner}>
          <div className={styles.langRow}>
            <button
              type="button"
              className={styles.langButton}
              aria-label="Language: English"
              onClick={() => handlePlaceholderAction('Language selection')}
            >
              <Globe size={iconSizes.md} strokeWidth={iconStroke} aria-hidden="true" />
              <span>English</span>
              <ChevronDown size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden="true" />
            </button>
          </div>

          <div className={styles.formBody}>
            <h2 className={styles.welcomeTitle}>Welcome to ETP</h2>
            <p className={styles.welcomeText}>
              Sign in to continue to{' '}
              <span className={styles.accentText}>Enterprise Technology Platform</span>
            </p>

            <form className={styles.form} onSubmit={handleSubmit} noValidate>
              <ETPInput
                name="username"
                label="Email or Username"
                placeholder="Enter your email or username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                error={errors.username}
                inputSize="lg"
                autoComplete="username"
                leadingIcon={
                  <User size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden="true" />
                }
              />

              <ETPInput
                name="password"
                label="Password"
                type={showPassword ? 'text' : 'password'}
                placeholder="Enter your password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                error={errors.password}
                inputSize="lg"
                autoComplete="current-password"
                leadingIcon={
                  <Lock size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden="true" />
                }
                trailingIcon={
                  showPassword ? (
                    <EyeOff size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden="true" />
                  ) : (
                    <Eye size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden="true" />
                  )
                }
                trailingActionLabel={
                  showPassword ? 'Hide password' : 'Show password'
                }
                onTrailingAction={() => setShowPassword((prev) => !prev)}
              />

              <div className={styles.formRow}>
                <ETPCheckbox
                  name="rememberMe"
                  label="Remember me"
                  checked={rememberMe}
                  onChange={(e) => setRememberMe(e.target.checked)}
                />
                <button
                  type="button"
                  className={styles.textLink}
                  onClick={() => handlePlaceholderAction('Forgot password')}
                >
                  Forgot password?
                </button>
              </div>

              <ETPButton type="submit" size="lg" fullWidth loading={loading}>
                Sign In
              </ETPButton>

              <div className={styles.divider}>
                <span className={styles.dividerLine} />
                <span>OR</span>
                <span className={styles.dividerLine} />
              </div>

              <ETPButton
                type="button"
                variant="secondary"
                size="lg"
                fullWidth
                className={styles.ssoButton}
                onClick={handleSsoClick}
              >
                <ShieldCheck
                  size={iconSizes.md}
                  strokeWidth={iconStroke}
                  aria-hidden="true"
                />
                Sign in with SSO
              </ETPButton>
            </form>
          </div>

          <footer className={styles.formFooter}>
            <nav className={styles.footerLinks} aria-label="Legal and support">
              <button
                type="button"
                className={styles.footerLink}
                onClick={() => handlePlaceholderAction('Privacy Policy')}
              >
                <Shield size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden="true" />
                Privacy Policy
              </button>
              <span className={styles.footerDivider} aria-hidden="true" />
              <button
                type="button"
                className={styles.footerLink}
                onClick={() => handlePlaceholderAction('Terms of Use')}
              >
                <FileText size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden="true" />
                Terms of Use
              </button>
              <span className={styles.footerDivider} aria-hidden="true" />
              <button
                type="button"
                className={styles.footerLink}
                onClick={() => handlePlaceholderAction('Contact Support')}
              >
                <Headphones size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden="true" />
                Contact Support
              </button>
            </nav>
          </footer>
        </div>
      </main>
    </div>
  );
}
