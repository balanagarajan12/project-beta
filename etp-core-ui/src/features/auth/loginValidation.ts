export interface LoginFormValues {
  username: string;
  password: string;
  rememberMe: boolean;
}

export interface LoginFormErrors {
  username?: string;
  password?: string;
}

export function validateLoginForm(values: LoginFormValues): LoginFormErrors {
  const errors: LoginFormErrors = {};

  if (!values.username.trim()) {
    errors.username = 'Email or username is required.';
  }

  if (!values.password) {
    errors.password = 'Password is required.';
  }

  return errors;
}
