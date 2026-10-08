package de.yawi.installer.core.error;

/** A command line argument the installer does not accept. */
public class InvalidArgumentsException extends InstallerException {

    private final String argument;

    public InvalidArgumentsException(String argument, String technical) {
        super(ErrorCode.INVALID_ARGUMENTS, "invalid argument '" + argument + "': " + technical, argument);
        this.argument = argument;
    }

    public String argument() {
        return argument;
    }
}
