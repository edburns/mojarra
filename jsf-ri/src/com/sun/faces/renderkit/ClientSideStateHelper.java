package com.sun.faces.renderkit;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.faces.FacesException;
import javax.faces.context.FacesContext;
import javax.faces.context.ResponseWriter;

import static com.sun.faces.config.WebConfiguration.WebContextInitParameter.ClientStateTimeout;
import static com.sun.faces.config.WebConfiguration.WebContextInitParameter.ClientStateWriteBufferSize;
import static com.sun.faces.config.WebConfiguration.WebEnvironmentEntry.ClientStateSavingPassword;
import com.sun.faces.io.Base64InputStream;
import com.sun.faces.io.Base64OutputStreamWriter;
import com.sun.faces.util.FacesLogger;

/**
 * <p>
 * This <code>StateHelper</code> provides the functionality associated with client-side state saving.
 * </p>
 */
public class ClientSideStateHelper extends StateHelper {

    private static final Logger LOGGER = FacesLogger.APPLICATION.getLogger();

    /**
     * <p>
     * Enabled encryption of view state.  Encryption is disabled by default.
     * </p>
     *
     * @see {@link com.sun.faces.config.WebConfiguration.WebEnvironmentEntry#ClientStateSavingPassword}
     */
    private ByteArrayGuard guard;

    /**
     * <p>
     * Flag indicating whether or not client view state will be manipulated
     * for and checked against a configured timeout value.
     * </p>
     *
     * <p>
     * This flag is configured via the <code>WebContextInitParameter.ClientStateTimeout</code>
     * configuration option of <code>WebConfiguration</code> and is disabled by
     * default.
     * </p>
     *
     * @see {@link com.sun.faces.config.WebConfiguration.WebContextInitParameter#ClientStateTimeout}
     */
    private boolean stateTimeoutEnabled;

    /**
     * <p>
     * If <code>stateTimeoutEnabled</code> is <code>true</code> this value will
     * represent the time in seconds that a particular client view state is
     * valid for.
     * </p>
     *
     * @see {@link com.sun.faces.config.WebConfiguration.WebContextInitParameter#ClientStateTimeout}
     */
    private long stateTimeout;

    /**
     * <p>
     * Client state is generally large, so this allows some tuning to control
     * the buffer that's used to write the client state.
     * </p>
     *
     * <p>
     * The value specified must be divisable by two as the buffer is split
     * between character and bytes (due to how client state is written).  By
     * default, the buffer size is 8192 (per request).
     * </p>
     *
     * @see {@link com.sun.faces.config.WebConfiguration.WebContextInitParameter#ClientStateWriteBufferSize}
     */
    private int csBuffSize;


    // ------------------------------------------------------------ Constructors


    /**
     * Construct a new <code>ClientSideStateHelper</code> instance.
     */
    public ClientSideStateHelper() {

        init();
        
    }


    // ------------------------------------------------ Methods from StateHelper


    /**
     * <p>
     * Writes the view state as a String generated by Base64 encoding the
     * Java Serialziation representation of the provided <code>state</code>
     * </p>
     *
     * <p>If <code>stateCapture</code> is <code>null</code>, the Base64 encoded
     * state will be written to the client as a hidden field using the <code>ResponseWriter</code>
     * from the provided <code>FacesContext</code>.</p>
     *
     * <p>If <code>stateCapture</code> is not <code>null</code>, the Base64 encoded
     * state will be appended to the provided <code>StringBuilder</code> without any markup
     * included or any content written to the client.
     *
     * @see {@link com.sun.faces.renderkit.StateHelper#writeState(javax.faces.context.FacesContext, Object, StringBuilder)}
     */
    public void writeState(FacesContext ctx,
                           Object state,
                           StringBuilder stateCapture) throws IOException {

        if (stateCapture != null) {
            doWriteState(state, new StringBuilderWriter(stateCapture));
        } else {
            ResponseWriter writer = ctx.getResponseWriter();
            writer.write(stateFieldStart);
            doWriteState(state, writer);
            writer.write(stateFieldEnd);
            writeRenderKitIdField(ctx, writer);
        }

    }


    /**
     * <p>Inspects the incoming request parameters for the standardized state
     * parameter name.  In this case, the parameter value will be a Base64 encoded
     * string previously encoded by {@link com.sun.faces.renderkit.ServerSideStateHelper#writeState(javax.faces.context.FacesContext, Object, StringBuilder)}.</p>
     *
     * <p>The string will be Base64-decoded and the state reconstructed using standard
     * Java serialization.</p>
     *
     * @see {@link com.sun.faces.renderkit.StateHelper#getState(javax.faces.context.FacesContext, String)}
     */
    public Object getState(FacesContext ctx, String viewId) throws IOException {


        String stateString = getStateParamValue(ctx);
        if (stateString == null) {
            return null;
        }
        return doGetState(stateString);

    }


    // ------------------------------------------------------- Protected Methods


    /**
     * Rebuilds the view state from the Base64 included String included
     * with the request.
     *
     * @param stateString the Base64 encoded view state
     * @return the view state reconstructed from <code>stateString</code>
     */
    protected Object doGetState(String stateString) {
        ObjectInputStream ois = null;
        try {
            ois = initInputStream(stateString);

            long stateTime = 0;
            if (stateTimeoutEnabled) {
                try {
                    stateTime = ois.readLong();
                } catch (IOException ioe) {
                    // we've caught an exception trying to read the time
                    // marker.  This most likely means a view that has been
                    // around before upgrading to the release that included
                    // this feature.  So, no marker, return null now to
                    // cause a ViewExpiredException
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine("Client state timeout is enabled, but unable to find the "
                              + "time marker in the serialized state.  Assuming state "
                              + "to be old and returning null.");
                    }
                    return null;
                }
            }
            Object structure = ois.readObject();
            Object state = ois.readObject();
            if (stateTime != 0 && hasStateExpired(stateTime)) {
                // return null if state has expired.  This should cause
                // a ViewExpiredException to be thrown
                return null;
            }

            return new Object[] { structure, state };

        } catch (java.io.OptionalDataException ode) {
            LOGGER.log(Level.SEVERE, ode.getMessage(), ode);
            throw new FacesException(ode);
        } catch (ClassNotFoundException cnfe) {
            LOGGER.log(Level.SEVERE, cnfe.getMessage(), cnfe);
            throw new FacesException(cnfe);
        } catch (IOException iox) {
            LOGGER.log(Level.SEVERE, iox.getMessage(), iox);
            throw new FacesException(iox);
        } finally {
            if (ois != null) {
                try {
                    ois.close();
                } catch (IOException ioe) {
                    // ignore
                }
            }
        }
    }


    /**
     * Serializes and Base64 encodes the provided <code>state</code> to the
     * provided <code>writer</code>/
     *
     * @param state view state
     * @param writer the <code>Writer</code> to write the content to
     * @throws IOException if an error occurs writing the state to the client
     */
    protected void doWriteState(Object state, Writer writer)
    throws IOException {

        Object[] stateToWrite = (Object[]) state;
        ObjectOutputStream oos = null;
        try {

            Base64OutputStreamWriter bos =
                  new Base64OutputStreamWriter(csBuffSize,
                                               writer);
            oos = initOutputStream(bos);

            if (stateTimeoutEnabled) {
                oos.writeLong(System.currentTimeMillis());
            }
            //noinspection NonSerializableObjectPassedToObjectStream
            oos.writeObject(stateToWrite[0]);
            //noinspection NonSerializableObjectPassedToObjectStream
            oos.writeObject(stateToWrite[1]);
            oos.flush();
            oos.close();

            // flush everything to the underlying writer
            bos.finish();

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE,
                           "Client State: total number of characters written: {0}",
                           bos.getTotalCharsWritten());
            }
        } finally {
            if (oos != null) {
                try {
                    oos.close();
                } catch (IOException ioe) {
                    // ignore
                }
            }

        }
    }


    /**
     * @param stateString the state string from which Objects will be
     *  deserialized from.
     * @return an <code>ObjectInputStream</code> configured appropriately
     *  based on the user configuration from which to read objects from.
     * @throws IOException if any issues arrise reading the state
     */
    private ObjectInputStream initInputStream(String stateString)
    throws IOException {

        InputStream bis;
        if (compressViewState) {
            bis = new GZIPInputStream(new Base64InputStream(stateString));
        } else {
            bis = new Base64InputStream(stateString);
        }

        ObjectInputStream ois;
        if (guard != null) {
            ois = serialProvider
                  .createObjectInputStream(new CipherInputStream(bis, guard.getDecryptionCipher()));
        } else {
            ois = serialProvider.createObjectInputStream(bis);
        }
        return ois;

    }


    /**
     * @param bos the Base64OutputStream to ultimately write the seriazed
     *  objects to
     * @return an <code>ObjectOutputStream</code> configured appropriately
     *  based on the user configuration from which to write objects to
     * @throws IOException if any issues arrise reading the state
     */
    protected ObjectOutputStream initOutputStream(Base64OutputStreamWriter bos)
    throws IOException {

        OutputStream base;
        if (compressViewState) {
            base = new GZIPOutputStream(bos, 1024);
        } else {
            base = bos;
        }

        ObjectOutputStream oos;
        if (guard != null) {
            oos = serialProvider.createObjectOutputStream(
                  new BufferedOutputStream(
                        new CipherOutputStream(base, guard.getEncryptionCipher())));
        } else {
            oos = serialProvider
                  .createObjectOutputStream(new BufferedOutputStream(
                        base,
                        1024));
        }
        return oos;

    }


    /**
     * <p>If the {@link com.sun.faces.config.WebConfiguration.WebContextInitParameter#ClientStateTimeout} init parameter
     * is set, calculate the elapsed time between the time the client state was
     * written and the time this method was invoked during restore.  If the client
     * state has expired, return <code>true</code>.  If the client state hasn't expired,
     * or the init parameter wasn't set, return <code>false</code>.
     * @param stateTime the time in milliseconds that the state was written
     *  to the client
     * @return <code>false</code> if the client state hasn't timed out, otherwise
     *  return <code>true</code>
     */
    protected boolean hasStateExpired(long stateTime) {

        if (stateTimeoutEnabled) {
            long elapsed = (System.currentTimeMillis() - stateTime) / 60000;
            return (elapsed > stateTimeout);
        } else {
            return false;
        }

    }


    /**
     * <p>
     * Initialze the various configuration options for client-side
     * sate saving.
     * </p>
     */
    protected void init() {

        String pass = webConfig.getEnvironmentEntry(
              ClientStateSavingPassword);
        if (pass != null) {
            guard = new ByteArrayGuard(pass);
        }

        stateTimeoutEnabled = webConfig.isSet(ClientStateTimeout);
        if (stateTimeoutEnabled) {
            String timeout = webConfig.getOptionValue(ClientStateTimeout);
            try {
                stateTimeout = Long.parseLong(timeout);
            } catch (NumberFormatException nfe) {
                stateTimeout = Long.parseLong(ClientStateTimeout.getDefaultValue());
            }
        }


        String size = webConfig.getOptionValue(
              ClientStateWriteBufferSize);
        String defaultSize =
              ClientStateWriteBufferSize.getDefaultValue();
        try {
            csBuffSize = Integer.parseInt(size);
            if (csBuffSize % 2 != 0) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING,
                               "jsf.renderkit.resstatemgr.clientbuf_div_two",
                               new Object[]{
                                     ClientStateWriteBufferSize.getQualifiedName(),
                                     size,
                                     defaultSize});
                }
                csBuffSize = Integer.parseInt(defaultSize);
            } else {
                csBuffSize /= 2;
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Using client state buffer size of "
                                + csBuffSize);
                }
            }
        } catch (NumberFormatException nfe) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING,
                           "jsf.renderkit.resstatemgr.clientbuf_not_integer",
                           new Object[]{
                                 ClientStateWriteBufferSize.getQualifiedName(),
                                 size,
                                 defaultSize});
            }
            csBuffSize = Integer.parseInt(defaultSize);
        }

    }


    // ----------------------------------------------------------- Inner Classes


    /**
     * A simple <code>Writer</code> implementation to encapsulate a
     * <code>StringBuilder</code> instance.
     */
    protected static final class StringBuilderWriter extends Writer {

        private StringBuilder sb;


        // -------------------------------------------------------- Constructors


        protected StringBuilderWriter(StringBuilder sb) {

            this.sb = sb;

        }


        // ------------------------------------------------- Methods from Writer


        @Override
        public void write(int c) throws IOException {

            sb.append((char) c);

        }


        @Override
        public void write(char cbuf[]) throws IOException {

            sb.append(cbuf);

        }


        @Override
        public void write(String str) throws IOException {

            sb.append(str);

        }


        @Override
        public void write(String str, int off, int len) throws IOException {

            sb.append(str.toCharArray(), off, len);

        }


        @Override
        public Writer append(CharSequence csq) throws IOException {

            sb.append(csq);
            return this;

        }


        @Override
        public Writer append(CharSequence csq, int start, int end)
        throws IOException {

            sb.append(csq, start, end);
            return this;

        }

        @Override
        public Writer append(char c) throws IOException {

            sb.append(c);
            return this;

        }

        public void write(char cbuf[], int off, int len) throws IOException {

            sb.append(cbuf, off, len);

        }

        public void flush() throws IOException {

            //no-op

        }

        public void close() throws IOException {

            //no-op

        }

    } // END StringBuilderWriter
}
