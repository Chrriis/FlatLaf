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

package com.formdev.flatlaf.ui;

import static com.formdev.flatlaf.util.UIScale.scale;
import java.awt.Color;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import com.formdev.flatlaf.util.UIScale;

/**
 * Provides the Flat LaF UI delegate for {@link javax.swing.JComboBox}.
 *
 * @author Karl Tauber
 */
public class FlatComboBoxUI
	extends BasicComboBoxUI
{
	public static ComponentUI createUI( JComponent c ) {
		return new FlatComboBoxUI();
	}

	@Override
	protected void installDefaults() {
		super.installDefaults();

		padding = UIScale.scale( padding );
	}

	@Override
	protected LayoutManager createLayoutManager() {
		return new BasicComboBoxUI.ComboBoxLayoutManager() {
			@Override
			public void layoutContainer( Container parent ) {
				super.layoutContainer( parent );

				if ( editor != null && padding != null ) {
					// fix editor bounds by subtracting padding
					editor.setBounds( FlatUIUtils.subtract( editor.getBounds(), padding ) );
				}
			}
		};
	}

	@Override
	protected PropertyChangeListener createPropertyChangeListener() {
		return new BasicComboBoxUI.PropertyChangeHandler() {
			@Override
			public void propertyChange( PropertyChangeEvent e ) {
				super.propertyChange( e );

				Object source = e.getSource();
				String propertyName = e.getPropertyName();

				if( editor != null &&
					((source == comboBox && propertyName == "background") ||
					 (source == editor && propertyName == "enabled")) )
				{
					// fix editor component background color
					updateEditorBackground();
				}
			}
		};
	}

	@Override
	protected void configureEditor() {
		super.configureEditor();

		updateEditorBackground();
	}

	private void updateEditorBackground() {
		editor.setBackground( editor.isEnabled()
			? comboBox.getBackground()
			: UIManager.getColor( "ComboBox.disabledBackground" ) );
	}

	@Override
	protected JButton createArrowButton() {
		return new FlatArrowButton();
	}

	@Override
	public void update( Graphics g, JComponent c ) {
		if( c.isOpaque() ) {
			FlatUIUtils.paintParentBackground( g, c );

			Graphics2D g2 = (Graphics2D) g;
			FlatUIUtils.setRenderingHints( g2 );

			int width = c.getWidth();
			int height = c.getHeight();
			float focusWidth = FlatUIUtils.getFocusWidth( c );
			float arc = FlatUIUtils.getComponentArc( c );
			int arrowX = arrowButton.getX();

			// paint background
			g2.setColor( comboBox.isEnabled()
				? c.getBackground()
				: UIManager.getColor( "ComboBox.disabledBackground" ) );
			FlatUIUtils.fillRoundRectangle( g2, 0, 0, width, height, focusWidth, arc );

			// paint arrow button background
			g2.setColor( UIManager.getColor( comboBox.isEnabled()
				? (comboBox.isEditable()
					? "ComboBox.buttonEditableBackground"
					: "ComboBox.buttonBackground" )
				: "ComboBox.disabledBackground" ) );
			Shape oldClip = g2.getClip();
			g2.clipRect( arrowX, 0, width - arrowX, height );
			FlatUIUtils.fillRoundRectangle( g2, 0, 0, width, height, focusWidth, arc );
			g2.setClip( oldClip );

			if( comboBox.isEditable() ) {
				// paint vertical line between value and arrow button
				g2.setColor( FlatUIUtils.getBorderColor( comboBox.isEnabled(), false ) );
				g2.fill( new Rectangle2D.Float( arrowX, focusWidth, scale( 1f ), height - (focusWidth * 2) ) );
			}
		}

		paint( g, c );
	}

	//---- class FlatArrowButton ----------------------------------------------

	private static class FlatArrowButton
		extends BasicArrowButton
	{
		FlatArrowButton() {
			super( SOUTH, Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE );

			setOpaque( false );
			setBorder( null );
		}

		@Override
		public void paint( Graphics g ) {
			FlatUIUtils.setRenderingHints( (Graphics2D) g );

			int w = scale( 9 );
			int h = scale( 5 );
			int x = Math.round( (getWidth() - w) / 2f );
			int y = Math.round( (getHeight() - h) / 2f );

			Path2D arrow = new Path2D.Float();
			arrow.moveTo( x, y );
			arrow.lineTo( x + w, y );
			arrow.lineTo( x + (w / 2f), y + h );
			arrow.closePath();

			g.setColor( UIManager.getColor( isEnabled()
				? "ComboBox.buttonArrowColor"
				: "ComboBox.buttonDisabledArrowColor" ) );
			((Graphics2D)g).fill( arrow );
		}
	}
}