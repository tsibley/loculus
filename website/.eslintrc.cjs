module.exports = {
    extends: ['plugin:@typescript-eslint/recommended', 'plugin:astro/recommended'],
    env: {
        es6: true,
        node: true,
    },
    parser: '@typescript-eslint/parser',
    parserOptions: {
        project: 'tsconfig.eslint.json',
        sourceType: 'module',
        tsConfigRootDir: __dirname,
        warnOnUnsupportedTypeScriptVersion: true,
    },
    plugins: [
        'eslint-plugin-import',
        'eslint-plugin-prefer-arrow',
        '@typescript-eslint',
        'eslint-plugin-react',
        'react-hooks',
        '@tanstack/eslint-plugin-query',
    ],
    settings: {
        react: {
            pragma: 'React', // Pragma to use, default to "React"
            version: 'detect', // React version. "detect" automatically picks the version you have installed.
        },
    },
    ignorePatterns: ['dist', '.eslintrc.cjs', 'tailwind.config.cjs', 'astro.config.mjs', 'colors.cjs'],
    overrides: [
        {
            // Define the configuration for `.astro` file.
            files: ['*.astro'],
            // Allows Astro components to be parsed.
            parser: 'astro-eslint-parser',
            // Parse the script in `.astro` as TypeScript by adding the following configuration.
            // It's the setting you need when using TypeScript.
            parserOptions: {
                parser: '@typescript-eslint/parser',
                extraFileExtensions: ['.astro'],
            },
            rules: {
                '@typescript-eslint/naming-convention': 'off',
                'react/jsx-no-useless-fragment': 'off',
            },
        },
        {
            // Prettier is stubborn, need to accept its rules in case of conflict
            // See https://github.com/loculus-project/loculus/pull/283#issuecomment-1733872357
            files: ['*'],
            rules: {
                'react/self-closing-comp': 'off',
                'react/forbid-component-props': 'off', // icons accept a style prop
            },
        },
    ],
    rules: {
        '@typescript-eslint/adjacent-overload-signatures': 'error',
        '@typescript-eslint/array-type': [
            'error',
            {
                default: 'array',
            },
        ],
        '@typescript-eslint/consistent-type-assertions': 'off',
        '@typescript-eslint/dot-notation': 'error',
        '@typescript-eslint/indent': 'off',
        '@typescript-eslint/member-delimiter-style': [
            'off',
            {
                multiline: {
                    delimiter: 'none',
                    requireLast: true,
                },
                singleline: {
                    delimiter: 'semi',
                    requireLast: false,
                },
            },
        ],
        '@typescript-eslint/naming-convention': [
            'error',
            {
                selector: 'default',
                format: ['camelCase'],
                leadingUnderscore: 'allow',
                trailingUnderscore: 'allow',
            },
            {
                selector: 'variable',
                format: ['camelCase', 'UPPER_CASE', 'PascalCase'],
                leadingUnderscore: 'allow',
                trailingUnderscore: 'allow',
            },
            {
                selector: 'property',
                format: null,
            },
            {
                selector: 'enumMember',
                format: ['camelCase', 'UPPER_CASE', 'PascalCase'],
            },
            {
                selector: 'import',
                format: null,
            },
            {
                selector: 'typeLike',
                format: ['PascalCase'],
            },
        ],
        '@typescript-eslint/no-empty-function': 'error',
        '@typescript-eslint/no-empty-interface': 'error',
        '@typescript-eslint/no-explicit-any': 'off',
        '@typescript-eslint/no-floating-promises': ['error', { ignoreVoid: true }],
        '@typescript-eslint/no-for-in-array': 'error',
        '@typescript-eslint/no-misused-new': 'error',
        '@typescript-eslint/no-namespace': 'error',
        '@typescript-eslint/no-parameter-properties': 'off',
        '@typescript-eslint/no-this-alias': 'error',
        '@typescript-eslint/no-unnecessary-qualifier': 'error',
        '@typescript-eslint/no-unnecessary-type-arguments': 'error',
        '@typescript-eslint/no-unnecessary-type-assertion': 'error',
        '@typescript-eslint/no-unused-expressions': 'error',
        '@typescript-eslint/no-use-before-define': 'off',
        '@typescript-eslint/no-var-requires': 'error',
        '@typescript-eslint/only-throw-error': 'error',
        '@typescript-eslint/prefer-for-of': 'error',
        '@typescript-eslint/prefer-function-type': 'error',
        '@typescript-eslint/prefer-namespace-keyword': 'error',
        '@typescript-eslint/prefer-readonly': 'error',
        '@typescript-eslint/promise-function-async': 'off',
        '@typescript-eslint/quotes': 'off',
        '@typescript-eslint/restrict-plus-operands': 'error',
        '@typescript-eslint/no-unnecessary-condition': 'error',
        'no-unused-vars': 'off', //Note: you must disable base rule if @typescript-eslint/no-unused-vars is enabled
        '@typescript-eslint/no-unused-vars': [
            'error',
            {
                caughtErrors: 'none',
            },
        ],
        '@typescript-eslint/semi': ['off', null],
        '@typescript-eslint/strict-boolean-expressions': [
            'error',
            {
                allowNullableObject: true,
                allowNullableBoolean: false,
                allowNullableString: false,
            },
        ],
        '@typescript-eslint/triple-slash-reference': [
            'error',
            {
                path: 'always',
                types: 'prefer-import',
                lib: 'always',
            },
        ],
        '@typescript-eslint/type-annotation-spacing': 'off',
        '@typescript-eslint/unified-signatures': 'error',
        '@typescript-eslint/no-redeclare': 'error',
        'arrow-parens': ['off', 'always'],
        'brace-style': ['off', 'off'],
        'comma-dangle': 'off',
        'complexity': 'off',
        'constructor-super': 'error',
        'eol-last': 'off',
        'eqeqeq': ['error', 'smart'],
        'guard-for-in': 'error',
        'id-blacklist': [
            'error',
            'any',
            'Number',
            'number',
            'String',
            'string',
            'Boolean',
            'boolean',
            'Undefined',
            'undefined',
        ],
        'id-match': 'error',
        'import/no-cycle': 'error',
        'import/no-deprecated': 'error',
        'import/no-extraneous-dependencies': 'off',
        'import/no-internal-modules': 'off',
        'import/order': [
            'error',
            {
                'groups': ['builtin', 'external', 'internal'],
                'newlines-between': 'always',
                'alphabetize': { order: 'asc' },
            },
        ],
        'linebreak-style': 'off',
        'max-classes-per-file': 'off',
        'max-len': 'off',
        'new-parens': 'off',
        'newline-per-chained-call': 'off',
        'no-bitwise': 'error',
        'no-caller': 'error',
        'no-cond-assign': 'error',
        'no-console': 'error',
        'no-debugger': 'error',
        'no-duplicate-case': 'error',
        'no-duplicate-imports': 'error',
        'no-empty': 'error',
        'no-eval': 'error',
        'no-extra-bind': 'error',
        'no-extra-semi': 'off',
        'no-fallthrough': 'off',
        'no-invalid-this': 'off',
        'no-irregular-whitespace': 'off',
        'no-multiple-empty-lines': 'off',
        'no-new-func': 'error',
        'no-new-wrappers': 'error',
        'no-redeclare': 'off',
        'no-return-await': 'error',
        'no-sequences': 'error',
        'no-sparse-arrays': 'error',
        'no-template-curly-in-string': 'error',
        'no-trailing-spaces': 'off',
        'no-undef-init': 'error',
        'no-unsafe-finally': 'error',
        'no-unused-labels': 'error',
        'no-var': 'error',
        'no-void': ['error', { allowAsStatement: true }],
        'object-shorthand': 'error',
        'one-var': ['error', 'never'],
        'prefer-const': 'error',
        'quote-props': 'off',
        'radix': 'error',
        'react/jsx-curly-spacing': 'off',
        'react/jsx-equals-spacing': 'off',
        'react/jsx-wrap-multilines': 'off',
        'space-before-function-paren': 'off',
        'space-in-parens': ['off', 'never'],
        'spaced-comment': [
            'error',
            'always',
            {
                markers: ['/'],
            },
        ],
        'use-isnan': 'error',
        'valid-typeof': 'off',
        '@typescript-eslint/explicit-member-accessibility': [
            'error',
            {
                overrides: {
                    constructors: 'no-public',
                },
            },
        ],
        '@typescript-eslint/member-ordering': 'error',
        '@typescript-eslint/consistent-type-imports': [
            'error',
            {
                fixStyle: 'inline-type-imports',
            },
        ],
        'no-restricted-imports': [
            'error',
            {
                patterns: ['.*/**/dist'],
            },
        ],
        'react/destructuring-assignment': ['error', 'always'],
        'react/function-component-definition': [
            'error',
            {
                namedComponents: 'arrow-function',
                unnamedComponents: 'arrow-function',
            },
        ],
        'react/no-deprecated': 'error',
        'react/no-children-prop': 'error',
        'react/no-is-mounted': 'error',
        'react/no-unstable-nested-components': 'warn',
        'react/forbid-component-props': ['error', { forbid: ['style'] }],
        'react/self-closing-comp': 'error',
        'react/void-dom-elements-no-children': 'error',
        'react/jsx-boolean-value': ['warn', 'never'],
        'react/jsx-curly-brace-presence': ['warn', 'never'],
        'react/jsx-fragments': ['error', 'syntax'],
        'react/jsx-uses-react': 'error',
        'react/jsx-pascal-case': 'error',
        'react/jsx-no-useless-fragment': 'error',
        'react/no-string-refs': 'error',
        'react-hooks/rules-of-hooks': 'error',
        'react-hooks/exhaustive-deps': 'error',
    },
};
