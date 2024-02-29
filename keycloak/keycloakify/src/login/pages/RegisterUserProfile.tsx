// ejected using 'npx eject-keycloak-page'
import { useState } from "react";
import { clsx } from "keycloakify/tools/clsx";
import { UserProfileFormFields } from "./shared/UserProfileFormFields";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import { useGetClassName } from "keycloakify/login/lib/useGetClassName";
import type { KcContext } from "../kcContext";
import type { I18n } from "../i18n";

export default function RegisterUserProfile(props: PageProps<Extract<KcContext, { pageId: "register-user-profile.ftl" }>, I18n>) {
    const { kcContext, i18n, doUseDefaultCss, Template, classes } = props;
    const [didAgree, setDidAgree] = useState(false);
    const { getClassName } = useGetClassName({
        doUseDefaultCss,
        classes
    });

    const { url, messagesPerField, recaptchaRequired, recaptchaSiteKey } = kcContext;

    const { msg, msgStr } = i18n;

    const [isFormSubmittable, setIsFormSubmittable] = useState(false);

    return (
        <Template
            {...{ kcContext, i18n, doUseDefaultCss, classes }}
            displayMessage={messagesPerField.exists("global")}
            displayRequiredFields={true}
            headerNode={msg("registerTitle")}
        >
            <form id="kc-register-form" className={getClassName("kcFormClass")} action={url.registrationAction} method="post">
                <UserProfileFormFields
                    kcContext={kcContext}
                    onIsFormSubmittableValueChange={setIsFormSubmittable}
                    i18n={i18n}
                    getClassName={getClassName}
                />
                {recaptchaRequired && (
                    <div className="form-group">
                        <div className={getClassName("kcInputWrapperClass")}>
                            <div className="g-recaptcha" data-size="compact" data-sitekey={recaptchaSiteKey} />
                        </div>
                    </div>
                )}
                <div className={getClassName("kcFormGroupClass")} style={{ "marginBottom": 30 }}>
                    <div id="kc-form-options" className={getClassName("kcFormOptionsClass")}>
                        <div className={getClassName("kcFormOptionsWrapperClass")}>
                            <span>
                                <a href={url.loginUrl}>{msg("backToLogin")}</a>
                            </span>
                        </div>
                    </div>

                    <div>
                    This database is subject to particular terms of use. Some data is available under the restricted use terms, which state that
                    you may not publish focal analyses of this data without express permission of the authors.
                    Do you agree to these terms?
                    <div>
                    <input
                        type="checkbox"
                        id="terms"
                        name="terms"
                        onChange={(e) => {
                            setDidAgree(e.target.checked);
                        }}
                    /> I agree
</div>

                </div>

                    

                    <div id="kc-form-buttons" className={getClassName("kcFormButtonsClass")}>
                        <input
                            className={clsx(
                                getClassName("kcButtonClass"),
                                getClassName("kcButtonPrimaryClass"),
                                getClassName("kcButtonBlockClass"),
                                getClassName("kcButtonLargeClass")
                            )}
                            type="submit"
                            value={msgStr("doRegister")}
                            disabled={!isFormSubmittable || !didAgree}
                        />
                    </div>
                </div>
            </form>
        </Template>
    );
}
