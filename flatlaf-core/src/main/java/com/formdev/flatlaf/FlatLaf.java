/*
 * Copyright 2019 FormDev Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.formdev.flatlaf;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.KeyEventPostProcessor;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.AbstractButton;
import javax.swing.InputMap;
import javax.swing.JLabel;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIDefaults.LazyValue;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.basic.BasicLookAndFeel;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.text.html.HTMLEditorKit;
import com.formdev.flatlaf.util.SystemInfo;
import com.formdev.flatlaf.util.UIScale;

/**
 * The base class for all Flat LaFs.
 *
 * @author Karl Tauber
 */
public abstract class FlatLaf
	extends BasicLookAndFeel
{
	static final Logger LOG = Logger.getLogger( FlatLaf.class.getName() );

	private BasicLookAndFeel base;

	private String desktopPropertyName;
	private PropertyChangeListener desktopPropertyListener;

	private KeyEventPostProcessor mnemonicListener;
	private static boolean showMnemonics;
	private static WeakReference<Window> lastShowMnemonicWindow;

	private Consumer<UIDefaults> postInitialization;

	public static boolean install( LookAndFeel newLookAndFeel ) {
		try {
			UIManager.setLookAndFeel( newLookAndFeel );
			return true;
		} catch( Exception ex ) {
			LOG.log( Level.SEVERE, "FlatLaf: Failed to initialize look and feel '" + newLookAndFeel.getClass().getName() + "'.", ex );
			return false;
		}
	}

	/**
	 * Returns the look and feel identifier.
	 * <p>
	 * Syntax: "FlatLaf - ${theme-name}"
	 * <p>
	 * Use {@code UIManager.getLookAndFeel().getID().startsWith( "FlatLaf" )}
	 * to check whether the current look and feel is FlatLaf.
	 */
	@Override
	public String getID() {
		return "FlatLaf - " + getName();
	}

	public abstract boolean isDark();

	@Override
	public boolean isNativeLookAndFeel() {
		return false;
	}

	@Override
	public boolean isSupportedLookAndFeel() {
		return true;
	}

	@Override
	public void initialize() {
		getBase().initialize();

		super.initialize();

		// make sure that a plain popup factory is used (otherwise sub-menu rendering
		// is "jittery" on Mac, where AquaLookAndFeel installs its own popup factory)
		if( PopupFactory.getSharedInstance().getClass() != PopupFactory.class )
			PopupFactory.setSharedInstance( new PopupFactory() );

		// add mnemonic listener
		mnemonicListener = e -> {
			checkShowMnemonics( e );
			return false;
		};
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventPostProcessor( mnemonicListener );

		// listen to desktop property changes to update UI if system font or scaling changes
		if( SystemInfo.IS_WINDOWS ) {
			// Windows 10 allows increasing font size independent of scaling:
			//   Settings > Ease of Access > Display > Make text bigger (100% - 225%)
			desktopPropertyName = "win.messagebox.font";
		} else if( SystemInfo.IS_LINUX ) {
			// Linux/Gnome allows extra scaling and larger text:
			//   Settings > Devices > Displays > Scale (100% or 200%)
			//   Settings > Universal access > Large Text (off or on, 125%)
			desktopPropertyName = "gnome.Xft/DPI";
		}
		if( desktopPropertyName != null ) {
			desktopPropertyListener = e -> {
				reSetLookAndFeel();
			};
			Toolkit.getDefaultToolkit().addPropertyChangeListener( desktopPropertyName, desktopPropertyListener );
		}

		// Following code should be ideally in initialize(), but needs color from UI defaults.
		// Do not move this code to getDefaults() to avoid side effects in the case that
		// getDefaults() is directly invoked from 3rd party code. E.g. `new FlatLightLaf().getDefaults()`.
		postInitialization = defaults -> {
			// update link color in HTML text
			Color linkColor = defaults.getColor( "Component.linkColor" );
			if( linkColor != null ) {
				new HTMLEditorKit().getStyleSheet().addRule(
					String.format( "a { color: #%06x; }", linkColor.getRGB() & 0xffffff ) );
			}
		};
	}

	@Override
	public void uninitialize() {
		// remove desktop property listener
		if( desktopPropertyListener != null ) {
			Toolkit.getDefaultToolkit().removePropertyChangeListener( desktopPropertyName, desktopPropertyListener );
			desktopPropertyName = null;
			desktopPropertyListener = null;
		}

		// remove mnemonic listener
		if( mnemonicListener != null ) {
			KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventPostProcessor( mnemonicListener );
			mnemonicListener = null;
		}

		// restore default link color
		new HTMLEditorKit().getStyleSheet().addRule( "a { color: blue; }" );
		postInitialization = null;

		if( base != null )
			base.uninitialize();

		super.uninitialize();
	}

	/**
	 * Get/create base LaF. This is used to grab base UI defaults from different LaFs.
	 * E.g. on Mac from system dependent LaF, otherwise from Metal LaF.
	 */
	private BasicLookAndFeel getBase() {
		if( base == null ) {
			if( SystemInfo.IS_MAC ) {
				// use Mac Aqua LaF as base
				String aquaLafClassName = "com.apple.laf.AquaLookAndFeel";
				try {
					if( SystemInfo.IS_JAVA_9_OR_LATER ) {
						Method m = UIManager.class.getMethod( "createLookAndFeel", String.class );
						base = (BasicLookAndFeel) m.invoke( null, "Mac OS X" );
					} else
						base = (BasicLookAndFeel) Class.forName( aquaLafClassName ).newInstance();
				} catch( Exception ex ) {
					LOG.log( Level.SEVERE, "FlatLaf: Failed to initialize base look and feel '" + aquaLafClassName + "'.", ex );
					throw new IllegalStateException();
				}
			} else
				base = new MetalLookAndFeel();
		}
		return base;
	}

	@Override
	public UIDefaults getDefaults() {
		UIDefaults defaults = getBase().getDefaults();
		UIDefaultsRemover.removeDefaults( defaults );

		// add Metal resource bundle, which is required for FlatFileChooserUI
		defaults.addResourceBundle( "com.sun.swing.internal.plaf.metal.resources.metal" );

		// initialize some defaults (for overriding) that are used in basic UI delegates,
		// but are not set in MetalLookAndFeel or BasicLookAndFeel
		Color control = defaults.getColor( "control" );
		defaults.put( "EditorPane.disabledBackground", control );
		defaults.put( "EditorPane.inactiveBackground", control );
		defaults.put( "FormattedTextField.disabledBackground", control );
		defaults.put( "PasswordField.disabledBackground", control );
		defaults.put( "TextArea.disabledBackground", control );
		defaults.put( "TextArea.inactiveBackground", control );
		defaults.put( "TextField.disabledBackground", control );
		defaults.put( "TextPane.disabledBackground", control );
		defaults.put( "TextPane.inactiveBackground", control );

		// initialize some own defaults (for overriding)
		defaults.put( "Spinner.disabledBackground", control );
		defaults.put( "Spinner.disabledForeground", control );

		// remember MenuBarUI from Mac Aqua LaF if Mac screen menubar is enabled
		boolean useScreenMenuBar = SystemInfo.IS_MAC && "true".equals( System.getProperty( "apple.laf.useScreenMenuBar" ) );
		Object aquaMenuBarUI = useScreenMenuBar ? defaults.get( "MenuBarUI" ) : null;

		initFonts( defaults );
		initIconColors( defaults, isDark() );
		initInputMaps( defaults );

		// load defaults from properties
		List<Class<?>> lafClassesForDefaultsLoading = getLafClassesForDefaultsLoading();
		if( lafClassesForDefaultsLoading != null )
			UIDefaultsLoader.loadDefaultsFromProperties( lafClassesForDefaultsLoading, defaults );
		else
			UIDefaultsLoader.loadDefaultsFromProperties( getClass(), defaults );

		// use Aqua MenuBarUI if Mac screen menubar is enabled
		if( useScreenMenuBar )
			defaults.put( "MenuBarUI", aquaMenuBarUI );

		invokePostInitialization( defaults );

		return defaults;
	}

	void invokePostInitialization( UIDefaults defaults ) {
		if( postInitialization != null ) {
			postInitialization.accept( defaults );
			postInitialization = null;
		}
	}

	List<Class<?>> getLafClassesForDefaultsLoading() {
		return null;
	}

	private void initFonts( UIDefaults defaults ) {
		FontUIResource uiFont = null;

		if( SystemInfo.IS_WINDOWS ) {
			Font winFont = (Font) Toolkit.getDefaultToolkit().getDesktopProperty( "win.messagebox.font" );
			if( winFont != null )
				uiFont = new FontUIResource( winFont );

		} else if( SystemInfo.IS_MAC ) {
			Font font = defaults.getFont( "Label.font" );

			if( SystemInfo.IS_MAC_OS_10_11_EL_CAPITAN_OR_LATER ) {
				// use San Francisco Text font
				font = new FontUIResource( ".SF NS Text", font.getStyle(), font.getSize() );
			}

			uiFont = (font instanceof FontUIResource) ? (FontUIResource) font : new FontUIResource( font );

		} else if( SystemInfo.IS_LINUX ) {
			Font font = LinuxFontPolicy.getFont();
			uiFont = (font instanceof FontUIResource) ? (FontUIResource) font : new FontUIResource( font );
		}

		if( uiFont == null )
			return;

		uiFont = UIScale.applyCustomScaleFactor( uiFont );

		// override fonts
		for( Object key : defaults.keySet() ) {
			if( key instanceof String && (((String)key).endsWith( ".font" ) || ((String)key).endsWith( "Font" )) )
				defaults.put( key, uiFont );
		}

		// use smaller font for progress bar
		defaults.put( "ProgressBar.font", UIScale.scaleFont( uiFont, 0.85f ) );
	}

	/**
	 * Adds the default color palette for action icons and object icons to the given UIDefaults.
	 * <p>
	 * This method is public and static to allow using the color palette with
	 * other LaFs (e.g. Windows LaF). To do so invoke:
	 *   {@code FlatLaf.initIconColors( UIManager.getLookAndFeelDefaults(), false );}
	 * after
	 *   {@code UIManager.setLookAndFeel( ... );}.
	 * <p>
	 * The colors are based on IntelliJ Platform
	 *   <a href="https://jetbrains.design/intellij/principles/icons/#action-icons">Action icons</a>
	 * and
	 *   <a href="https://jetbrains.design/intellij/principles/icons/#noun-icons">Noun icons</a>
	 */
	public static void initIconColors( UIDefaults defaults, boolean dark ) {
		// colors for action icons
		// see https://jetbrains.design/intellij/principles/icons/#action-icons
		defaults.put( "Actions.Red",            new ColorUIResource( !dark ? 0xDB5860 : 0xC75450 ) );
		defaults.put( "Actions.Yellow",         new ColorUIResource( !dark ? 0xEDA200 : 0xF0A732 ) );
		defaults.put( "Actions.Green",          new ColorUIResource( !dark ? 0x59A869 : 0x499C54 ) );
		defaults.put( "Actions.Blue",           new ColorUIResource( !dark ? 0x389FD6 : 0x3592C4 ) );
		defaults.put( "Actions.Grey",           new ColorUIResource( !dark ? 0x6E6E6E : 0xAFB1B3 ) );
		defaults.put( "Actions.GreyInline",     new ColorUIResource( !dark ? 0x7F8B91 : 0x7F8B91 ) );

		// colors for object icons
		// see https://jetbrains.design/intellij/principles/icons/#noun-icons
		defaults.put( "Objects.Grey",           new ColorUIResource( 0x9AA7B0 ) );
		defaults.put( "Objects.Blue",           new ColorUIResource( 0x40B6E0 ) );
		defaults.put( "Objects.Green",          new ColorUIResource( 0x62B543 ) );
		defaults.put( "Objects.Yellow",         new ColorUIResource( 0xF4AF3D ) );
		defaults.put( "Objects.YellowDark",     new ColorUIResource( 0xD9A343 ) );
		defaults.put( "Objects.Purple",         new ColorUIResource( 0xB99BF8 ) );
		defaults.put( "Objects.Pink",           new ColorUIResource( 0xF98B9E ) );
		defaults.put( "Objects.Red",            new ColorUIResource( 0xF26522 ) );
		defaults.put( "Objects.RedStatus",      new ColorUIResource( 0xE05555 ) );
		defaults.put( "Objects.GreenAndroid",   new ColorUIResource( 0xA4C639 ) );
		defaults.put( "Objects.BlackText",      new ColorUIResource( 0x231F20 ) );
	}

	private void initInputMaps( UIDefaults defaults ) {
		if( SystemInfo.IS_MAC ) {
			// AquaLookAndFeel (the base for UI defaults on macOS) uses special
			// action keys (e.g. "aquaExpandNode") for some macOS specific behaviour.
			// Those action keys are not available in FlatLaf, which makes it
			// necessary to make some modifications.

			// combobox
			defaults.put( "ComboBox.ancestorInputMap", new UIDefaults.LazyInputMap( new Object[] {
				     "ESCAPE", "hidePopup",
				    "PAGE_UP", "pageUpPassThrough",
				  "PAGE_DOWN", "pageDownPassThrough",
				       "HOME", "homePassThrough",
				        "END", "endPassThrough",
				       "DOWN", "selectNext",
				    "KP_DOWN", "selectNext",
				      "SPACE", "spacePopup",
				      "ENTER", "enterPressed",
				         "UP", "selectPrevious",
				      "KP_UP", "selectPrevious"
			} ) );

			// tree node expanding/collapsing
			modifyInputMap( defaults, "Tree.focusInputMap",
				         "RIGHT", "selectChild",
				      "KP_RIGHT", "selectChild",
				          "LEFT", "selectParent",
				       "KP_LEFT", "selectParent",
				   "shift RIGHT", null,
				"shift KP_RIGHT", null,
				    "shift LEFT", null,
				 "shift KP_LEFT", null,
				     "ctrl LEFT", null,
				  "ctrl KP_LEFT", null,
				    "ctrl RIGHT", null,
				 "ctrl KP_RIGHT", null
			);
			defaults.put( "Tree.focusInputMap.RightToLeft", new UIDefaults.LazyInputMap( new Object[] {
	                     "RIGHT", "selectParent",
	                  "KP_RIGHT", "selectParent",
	                      "LEFT", "selectChild",
	                   "KP_LEFT", "selectChild"
			} ) );
		}
	}

	private void modifyInputMap( UIDefaults defaults, String key, Object... bindings ) {
		// Note: not using `defaults.get(key)` here because this would resolve the lazy value
		defaults.put( key, new LazyModifyInputMap( defaults.remove( key ), bindings ) );
	}

	private static void reSetLookAndFeel() {
		EventQueue.invokeLater( () -> {
			LookAndFeel lookAndFeel = UIManager.getLookAndFeel();
			try {
				// re-set current LaF
				UIManager.setLookAndFeel( lookAndFeel );

				// must fire property change events ourself because old and new LaF are the same
				PropertyChangeEvent e = new PropertyChangeEvent( UIManager.class, "lookAndFeel", lookAndFeel, lookAndFeel );
				for( PropertyChangeListener l : UIManager.getPropertyChangeListeners() )
					l.propertyChange( e );

				// update UI
				updateUI();
			} catch( UnsupportedLookAndFeelException ex ) {
				LOG.log( Level.SEVERE, "FlatLaf: Failed to reinitialize look and feel '" + lookAndFeel.getClass().getName() + "'.", ex );
			}
		} );
	}

	/**
	 * Update UI of all application windows.
	 * Invoke after changing LaF.
	 */
	public static void updateUI() {
		for( Window w : Window.getWindows() )
			SwingUtilities.updateComponentTreeUI( w );
	}

	public static boolean isShowMnemonics() {
		return showMnemonics || !UIManager.getBoolean( "Component.hideMnemonics" );
	}

	private static void checkShowMnemonics( KeyEvent e ) {
		int keyCode = e.getKeyCode();
		if( SystemInfo.IS_MAC ) {
			// Ctrl+Alt keys must be pressed on Mac
			if( keyCode == KeyEvent.VK_CONTROL || keyCode == KeyEvent.VK_ALT )
				showMnemonics( e.getID() == KeyEvent.KEY_PRESSED && e.isControlDown() && e.isAltDown(), e.getComponent() );
		} else {
			// Alt key must be pressed on Windows and Linux
			if( keyCode == KeyEvent.VK_ALT )
				showMnemonics( e.getID() == KeyEvent.KEY_PRESSED, e.getComponent() );
		}
	}

	private static void showMnemonics( boolean show, Component c ) {
		if( show == showMnemonics )
			return;

		showMnemonics = show;

		// check whether it is necessary to repaint
		if( !UIManager.getBoolean( "Component.hideMnemonics" ) )
			return;

		if( show ) {
			// get root pane
			JRootPane rootPane = SwingUtilities.getRootPane( c );
			if( rootPane == null )
				return;

			// get window
			Window window = SwingUtilities.getWindowAncestor( rootPane );
			if( window == null )
				return;

			// repaint components with mnemonics in focused window
			repaintMnemonics( window );

			lastShowMnemonicWindow = new WeakReference<>( window );
		} else if( lastShowMnemonicWindow != null ) {
			Window window = lastShowMnemonicWindow.get();
			if( window != null )
				repaintMnemonics( window );

			lastShowMnemonicWindow = null;
		}
	}

	private static void repaintMnemonics( Container container ) {
		for( Component c : container.getComponents() ) {
			if( !c.isVisible() )
				continue;

			if( hasMnemonic( c ) )
				c.repaint();

			if( c instanceof Container )
				repaintMnemonics( (Container) c );
		}
	}

	private static boolean hasMnemonic( Component c ) {
		if( c instanceof JLabel && ((JLabel)c).getDisplayedMnemonicIndex() >= 0 )
			return true;

		if( c instanceof AbstractButton && ((AbstractButton)c).getDisplayedMnemonicIndex() >= 0 )
			return true;

		if( c instanceof JTabbedPane ) {
			JTabbedPane tabPane = (JTabbedPane) c;
			int tabCount = tabPane.getTabCount();
			for( int i = 0; i < tabCount; i++ ) {
				if( tabPane.getDisplayedMnemonicIndexAt( i ) >= 0 )
					return true;
			}
		}

		return false;
	}

	//---- class LazyModifyInputMap -------------------------------------------

	/**
	 * Takes a (lazy) base input map and lazily applies modifications to it specified in bindings.
	 */
	private static class LazyModifyInputMap
		implements LazyValue
	{
		private final Object baseInputMap;
		private final Object[] bindings;

		public LazyModifyInputMap( Object baseInputMap, Object[] bindings ) {
			this.baseInputMap = baseInputMap;
			this.bindings = bindings;
		}

		@Override
		public Object createValue( UIDefaults table ) {
			// get base input map
			InputMap inputMap = (baseInputMap instanceof LazyValue)
				? (InputMap) ((LazyValue)baseInputMap).createValue( table )
				: (InputMap) baseInputMap;

			// modify input map (replace or remove)
			for( int i = 0; i < bindings.length; i += 2 ) {
				KeyStroke keyStroke = KeyStroke.getKeyStroke( (String) bindings[i] );
				if( bindings[i + 1] != null )
					inputMap.put( keyStroke, bindings[i + 1] );
				else
					inputMap.remove( keyStroke );
			}

			return inputMap;
		}
	}
}
