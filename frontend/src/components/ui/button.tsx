import { ButtonHTMLAttributes, forwardRef } from "react";
import { cn } from "@/lib/utils/cn";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: "primary" | "secondary" | "ghost" | "danger";
  size?: "sm" | "md" | "lg";
}

const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = "primary", size = "md", ...props }, ref) => {
    const base =
      "inline-flex items-center justify-center gap-2 font-semibold border border-transparent transition-all duration-200 " +
      "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gold focus-visible:ring-offset-2 focus-visible:ring-offset-paper " +
      "disabled:cursor-not-allowed disabled:bg-parchment disabled:text-ash disabled:border-hairline disabled:shadow-none disabled:translate-y-0";

    const variants: Record<NonNullable<ButtonProps["variant"]>, string> = {
      primary:
        "bg-gold text-paper hover:bg-gold-deep hover:shadow-[0_4px_14px_rgba(0,88,202,0.30)] hover:-translate-y-px",
      secondary:
        "bg-paper text-gold-deep border-gold hover:bg-ivory hover:text-gold-ink hover:border-gold-deep",
      ghost:
        "bg-transparent text-charcoal hover:bg-ivory",
      danger:
        "bg-burgundy text-paper hover:bg-[#7F2424]",
    };

    const sizes: Record<NonNullable<ButtonProps["size"]>, string> = {
      sm: "h-8 px-3 text-xs rounded-lg",
      md: "h-10 px-4 text-sm rounded-[10px]",
      lg: "h-12 px-5 text-sm rounded-xl",
    };

    return (
      <button
        ref={ref}
        className={cn(base, variants[variant], sizes[size], className)}
        {...props}
      />
    );
  }
);

Button.displayName = "Button";

export { Button };
